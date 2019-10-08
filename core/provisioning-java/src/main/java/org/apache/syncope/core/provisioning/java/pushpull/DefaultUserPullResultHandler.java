/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.syncope.core.provisioning.java.pushpull;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.syncope.common.lib.patch.AnyPatch;
import org.apache.syncope.common.lib.patch.LinkedAccountPatch;
import org.apache.syncope.common.lib.patch.UserPatch;
import org.apache.syncope.common.lib.to.AnyTO;
import org.apache.syncope.common.lib.to.AttrTO;
import org.apache.syncope.common.lib.to.LinkedAccountTO;
import org.apache.syncope.common.lib.to.PropagationStatus;
import org.apache.syncope.common.lib.to.UserTO;
import org.apache.syncope.common.lib.types.AnyTypeKind;
import org.apache.syncope.common.lib.types.AuditElements;
import org.apache.syncope.common.lib.types.AuditElements.Result;
import org.apache.syncope.common.lib.types.MatchingRule;
import org.apache.syncope.common.lib.types.PatchOperation;
import org.apache.syncope.common.lib.types.ResourceOperation;
import org.apache.syncope.common.lib.types.UnmatchingRule;
import org.apache.syncope.core.persistence.api.dao.PullMatch;
import org.apache.syncope.core.persistence.api.entity.AnyUtils;
import org.apache.syncope.core.persistence.api.entity.resource.Provision;
import org.apache.syncope.core.persistence.api.entity.user.LinkedAccount;
import org.apache.syncope.core.persistence.api.entity.user.User;
import org.apache.syncope.core.provisioning.api.PropagationByResource;
import org.apache.syncope.core.provisioning.api.ProvisioningManager;
import org.apache.syncope.core.provisioning.api.UserProvisioningManager;
import org.apache.syncope.core.provisioning.api.WorkflowResult;
import org.apache.syncope.core.provisioning.api.propagation.PropagationException;
import org.apache.syncope.core.provisioning.api.pushpull.ProvisioningReport;
import org.apache.syncope.core.provisioning.api.pushpull.PullActions;
import org.identityconnectors.framework.common.objects.SyncDelta;
import org.apache.syncope.core.provisioning.api.pushpull.UserPullResultHandler;
import org.identityconnectors.framework.common.objects.AttributeUtil;
import org.identityconnectors.framework.common.objects.SyncDeltaType;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

public class DefaultUserPullResultHandler extends AbstractPullResultHandler implements UserPullResultHandler {

    @Autowired
    private UserProvisioningManager userProvisioningManager;

    @Override
    protected AnyUtils getAnyUtils() {
        return anyUtilsFactory.getInstance(AnyTypeKind.USER);
    }

    @Override
    protected String getName(final AnyTO anyTO) {
        return UserTO.class.cast(anyTO).getUsername();
    }

    @Override
    protected ProvisioningManager<?, ?> getProvisioningManager() {
        return userProvisioningManager;
    }

    @Override
    protected AnyTO getAnyTO(final String key) {
        return userDataBinder.getUserTO(key);
    }

    @Override
    protected WorkflowResult<? extends AnyPatch> update(final AnyPatch patch) {
        WorkflowResult<Pair<UserPatch, Boolean>> update = uwfAdapter.update((UserPatch) patch);
        return new WorkflowResult<>(update.getResult().getLeft(), update.getPropByRes(), update.getPerformedTasks());
    }

    public Boolean enabled(final SyncDelta delta) {
        return profile.getTask().isSyncStatus() ? AttributeUtil.isEnabled(delta.getObject()) : null;
    }

    @Override
    protected AnyTO doCreate(final AnyTO anyTO, final SyncDelta delta) {
        Map.Entry<String, List<PropagationStatus>> created = userProvisioningManager.create(
                UserTO.class.cast(anyTO),
                true,
                true,
                enabled(delta),
                Collections.singleton(profile.getTask().getResource().getKey()),
                true);

        return getAnyTO(created.getKey());
    }

    @Override
    protected AnyPatch doUpdate(
            final AnyTO before,
            final AnyPatch anyPatch,
            final SyncDelta delta,
            final ProvisioningReport result) {

        Pair<UserPatch, List<PropagationStatus>> updated = userProvisioningManager.update(
                UserPatch.class.cast(anyPatch),
                result,
                enabled(delta),
                Collections.singleton(profile.getTask().getResource().getKey()),
                true);

        return updated.getLeft();
    }

    @Override
    protected void handleLinkedAccounts(
            final SyncDelta delta,
            final List<PullMatch> matches,
            final Provision provision,
            final AnyUtils anyUtils) throws JobExecutionException {

        for (PullMatch match : matches) {
            User user = userDAO.find(match.getLinkingUserKey());
            if (user == null) {
                LOG.error("Could not find linking user, cannot process match {}", match);
                return;
            }

            Optional<? extends LinkedAccount> found =
                    user.getLinkedAccount(provision.getResource().getKey(), delta.getUid().getUidValue());
            if (found.isPresent()) {
                LinkedAccount account = found.get();

                if (SyncDeltaType.CREATE_OR_UPDATE == delta.getDeltaType()) {
                    switch (profile.getTask().getMatchingRule()) {
                        case UPDATE:
                            update(delta, account, provision).ifPresent(profile.getResults()::add);
                            break;

                        case DEPROVISION:
                        case UNASSIGN:
                            deprovision(profile.getTask().getMatchingRule(), delta, account).
                                    ifPresent(profile.getResults()::add);
                            break;

                        case LINK:
                        case UNLINK:
                            LOG.warn("{} not applicable to linked accounts, ignoring",
                                    profile.getTask().getMatchingRule());
                            break;

                        case IGNORE:
                            profile.getResults().add(ignore(delta, account, true));
                            break;

                        default:
                        // do nothing
                    }
                } else if (SyncDeltaType.DELETE == delta.getDeltaType()) {
                    delete(delta, account).ifPresent(profile.getResults()::add);
                }
            } else {
                if (SyncDeltaType.CREATE_OR_UPDATE == delta.getDeltaType()) {
                    LinkedAccountTO accountTO = new LinkedAccountTO();
                    accountTO.setConnObjectKeyValue(delta.getUid().getUidValue());
                    accountTO.setResource(provision.getResource().getKey());

                    switch (profile.getTask().getUnmatchingRule()) {
                        case ASSIGN:
                        case PROVISION:
                            provision(profile.getTask().getUnmatchingRule(), delta, user, accountTO, provision).
                                    ifPresent(profile.getResults()::add);
                            break;

                        case IGNORE:
                            profile.getResults().add(ignore(delta, null, false));
                            break;

                        default:
                        // do nothing
                    }
                } else if (SyncDeltaType.DELETE == delta.getDeltaType()) {
                    finalize(
                            ResourceOperation.DELETE.name().toLowerCase(),
                            AuditElements.Result.SUCCESS,
                            null,
                            null,
                            delta);
                    LOG.debug("No match found for deletion");
                }
            }
        }
    }

    protected Optional<ProvisioningReport> deprovision(
            final MatchingRule matchingRule,
            final SyncDelta delta,
            final LinkedAccount account) throws JobExecutionException {

        if (!profile.getTask().isPerformUpdate()) {
            LOG.debug("PullTask not configured for update");
            finalize(MatchingRule.toEventName(MatchingRule.UPDATE), Result.SUCCESS, null, null, delta);
            return Optional.empty();
        }

        LOG.debug("About to deprovision {}", account);

        ProvisioningReport report = new ProvisioningReport();
        report.setOperation(ResourceOperation.DELETE);
        report.setAnyType(PullMatch.MatchTarget.LINKED_ACCOUNT.name());
        report.setStatus(ProvisioningReport.Status.SUCCESS);
        report.setKey(account.getKey());

        LinkedAccountTO before = userDataBinder.getLinkedAccountTO(account);

        if (!profile.isDryRun()) {
            Object output = before;
            Result resultStatus;

            try {
                if (matchingRule == MatchingRule.UNASSIGN) {
                    for (PullActions action : profile.getActions()) {
                        action.beforeUnassign(profile, delta, before);
                    }
                } else if (matchingRule == MatchingRule.DEPROVISION) {
                    for (PullActions action : profile.getActions()) {
                        action.beforeDeprovision(profile, delta, before);
                    }
                }

                PropagationByResource<Pair<String, String>> propByLinkedAccount = new PropagationByResource<>();
                propByLinkedAccount.add(
                        ResourceOperation.DELETE,
                        Pair.of(account.getResource().getKey(), account.getConnObjectKeyValue()));

                taskExecutor.execute(propagationManager.getDeleteTasks(
                        AnyTypeKind.USER,
                        account.getOwner().getKey(),
                        null,
                        propByLinkedAccount,
                        null),
                        false);

                for (PullActions action : profile.getActions()) {
                    action.after(profile, delta, before, report);
                }

                resultStatus = Result.SUCCESS;

                LOG.debug("Linked account {} successfully updated", account.getConnObjectKeyValue());
            } catch (PropagationException e) {
                // A propagation failure doesn't imply a pull failure.
                // The propagation exception status will be reported into the propagation task execution.
                LOG.error("Could not propagate linked acccount {}", account.getConnObjectKeyValue());
                output = e;
                resultStatus = Result.FAILURE;
            } catch (Exception e) {
                throwIgnoreProvisionException(delta, e);

                report.setStatus(ProvisioningReport.Status.FAILURE);
                report.setMessage(ExceptionUtils.getRootCauseMessage(e));
                LOG.error("Could not update linked account {}", account, e);
                output = e;
                resultStatus = Result.FAILURE;
            }

            finalize(MatchingRule.toEventName(matchingRule), resultStatus, before, output, delta);
        }

        return Optional.of(report);
    }

    protected Optional<ProvisioningReport> provision(
            final UnmatchingRule rule,
            final SyncDelta delta,
            final User user,
            final LinkedAccountTO accountTO,
            final Provision provision)
            throws JobExecutionException {

        if (!profile.getTask().isPerformCreate()) {
            LOG.debug("PullTask not configured for create");
            finalize(UnmatchingRule.toEventName(rule), Result.SUCCESS, null, null, delta);
            return Optional.empty();
        }

        LOG.debug("About to create {}", accountTO);

        ProvisioningReport report = new ProvisioningReport();
        report.setOperation(ResourceOperation.CREATE);
        report.setName(accountTO.getConnObjectKeyValue());
        report.setAnyType(PullMatch.MatchTarget.LINKED_ACCOUNT.name());
        report.setStatus(ProvisioningReport.Status.SUCCESS);

        if (profile.isDryRun()) {
            report.setKey(null);
            finalize(UnmatchingRule.toEventName(rule), Result.SUCCESS, null, null, delta);
        } else {
            UserTO owner = userDataBinder.getUserTO(user, false);
            UserTO connObject = connObjectUtils.getAnyTO(
                    delta.getObject(), profile.getTask(), provision, getAnyUtils(), false);

            if (connObject.getUsername().equals(owner.getUsername())) {
                accountTO.setUsername(null);
            } else if (!connObject.getUsername().equals(accountTO.getUsername())) {
                accountTO.setUsername(connObject.getUsername());
            }

            if (connObject.getPassword() != null) {
                accountTO.setPassword(connObject.getPassword());
            }

            accountTO.setSuspended(BooleanUtils.isTrue(BooleanUtils.negate(enabled(delta))));

            connObject.getPlainAttrs().forEach(connObjectAttr -> {
                Optional<AttrTO> ownerAttr = owner.getPlainAttr(connObjectAttr.getSchema());
                if (ownerAttr.isPresent() && ownerAttr.get().getValues().equals(connObjectAttr.getValues())) {
                    accountTO.getPlainAttrs().removeIf(attr -> connObjectAttr.getSchema().equals(attr.getSchema()));
                } else {
                    accountTO.getPlainAttrs().add(connObjectAttr);
                }
            });

            for (PullActions action : profile.getActions()) {
                if (rule == UnmatchingRule.ASSIGN) {
                    action.beforeAssign(profile, delta, accountTO);
                } else if (rule == UnmatchingRule.PROVISION) {
                    action.beforeProvision(profile, delta, accountTO);
                }
            }

            UserPatch patch = new UserPatch();
            patch.setKey(user.getKey());
            patch.getLinkedAccounts().add(new LinkedAccountPatch.Builder().
                    operation(PatchOperation.ADD_REPLACE).linkedAccountTO(accountTO).build());

            Result resultStatus;
            Object output;

            try {
                userProvisioningManager.update(
                        patch,
                        report,
                        null,
                        Collections.singleton(profile.getTask().getResource().getKey()),
                        true);
                resultStatus = Result.SUCCESS;

                LinkedAccountTO created = userDAO.find(patch.getKey()).
                        getLinkedAccount(accountTO.getResource(), accountTO.getConnObjectKeyValue()).
                        map(acct -> userDataBinder.getLinkedAccountTO(acct)).
                        orElse(null);
                output = created;
                resultStatus = Result.SUCCESS;

                for (PullActions action : profile.getActions()) {
                    action.after(profile, delta, created, report);
                }

                LOG.debug("Linked account {} successfully created", accountTO.getConnObjectKeyValue());
            } catch (PropagationException e) {
                // A propagation failure doesn't imply a pull failure.
                // The propagation exception status will be reported into the propagation task execution.
                LOG.error("Could not propagate linked acccount {}", accountTO.getConnObjectKeyValue());
                output = e;
                resultStatus = Result.FAILURE;
            } catch (Exception e) {
                throwIgnoreProvisionException(delta, e);

                report.setStatus(ProvisioningReport.Status.FAILURE);
                report.setMessage(ExceptionUtils.getRootCauseMessage(e));
                LOG.error("Could not create linked account {} ", accountTO.getConnObjectKeyValue(), e);
                output = e;
                resultStatus = Result.FAILURE;
            }

            finalize(UnmatchingRule.toEventName(rule), resultStatus, null, output, delta);
        }

        return Optional.of(report);
    }

    protected Optional<ProvisioningReport> update(
            final SyncDelta delta,
            final LinkedAccount account,
            final Provision provision)
            throws JobExecutionException {

        if (!profile.getTask().isPerformUpdate()) {
            LOG.debug("PullTask not configured for update");
            finalize(MatchingRule.toEventName(MatchingRule.UPDATE), Result.SUCCESS, null, null, delta);
            return Optional.empty();
        }

        LOG.debug("About to update {}", account);

        ProvisioningReport report = new ProvisioningReport();
        report.setOperation(ResourceOperation.UPDATE);
        report.setKey(account.getKey());
        report.setName(account.getConnObjectKeyValue());
        report.setAnyType(PullMatch.MatchTarget.LINKED_ACCOUNT.name());
        report.setStatus(ProvisioningReport.Status.SUCCESS);

        if (!profile.isDryRun()) {
            LinkedAccountTO before = userDataBinder.getLinkedAccountTO(account);

            UserTO owner = userDataBinder.getUserTO(account.getOwner(), false);
            UserTO connObject = connObjectUtils.getAnyTO(
                    delta.getObject(), profile.getTask(), provision, getAnyUtils(), false);

            LinkedAccountTO update = userDataBinder.getLinkedAccountTO(account);

            if (connObject.getUsername().equals(owner.getUsername())) {
                update.setUsername(null);
            } else if (!connObject.getUsername().equals(update.getUsername())) {
                update.setUsername(connObject.getUsername());
            }

            if (connObject.getPassword() != null) {
                update.setPassword(connObject.getPassword());
            }

            update.setSuspended(BooleanUtils.isTrue(BooleanUtils.negate(enabled(delta))));

            Set<String> attrsToRemove = new HashSet<>();
            connObject.getPlainAttrs().forEach(connObjectAttr -> {
                Optional<AttrTO> ownerAttr = owner.getPlainAttr(connObjectAttr.getSchema());
                if (ownerAttr.isPresent() && ownerAttr.get().getValues().equals(connObjectAttr.getValues())) {
                    attrsToRemove.add(connObjectAttr.getSchema());
                } else {
                    Optional<AttrTO> updateAttr = update.getPlainAttr(connObjectAttr.getSchema());
                    if (!updateAttr.isPresent() || !updateAttr.get().getValues().equals(connObjectAttr.getValues())) {
                        attrsToRemove.add(connObjectAttr.getSchema());
                        update.getPlainAttrs().add(connObjectAttr);
                    }
                }
            });
            update.getPlainAttrs().removeIf(attr -> attrsToRemove.contains(attr.getSchema()));

            UserPatch patch = new UserPatch();
            patch.setKey(account.getOwner().getKey());
            patch.getLinkedAccounts().add(new LinkedAccountPatch.Builder().
                    operation(PatchOperation.ADD_REPLACE).linkedAccountTO(update).build());

            for (PullActions action : profile.getActions()) {
                action.beforeUpdate(profile, delta, before, patch);
            }

            Result resultStatus;
            Object output;

            try {
                userProvisioningManager.update(
                        patch,
                        report,
                        null,
                        Collections.singleton(profile.getTask().getResource().getKey()),
                        true);
                resultStatus = Result.SUCCESS;

                LinkedAccountTO updated = userDAO.find(patch.getKey()).
                        getLinkedAccount(account.getResource().getKey(), account.getConnObjectKeyValue()).
                        map(acct -> userDataBinder.getLinkedAccountTO(acct)).
                        orElse(null);
                output = updated;
                resultStatus = Result.SUCCESS;

                for (PullActions action : profile.getActions()) {
                    action.after(profile, delta, updated, report);
                }

                LOG.debug("Linked account {} successfully updated", account.getConnObjectKeyValue());
            } catch (PropagationException e) {
                // A propagation failure doesn't imply a pull failure.
                // The propagation exception status will be reported into the propagation task execution.
                LOG.error("Could not propagate linked acccount {}", account.getConnObjectKeyValue());
                output = e;
                resultStatus = Result.FAILURE;
            } catch (Exception e) {
                throwIgnoreProvisionException(delta, e);

                report.setStatus(ProvisioningReport.Status.FAILURE);
                report.setMessage(ExceptionUtils.getRootCauseMessage(e));
                LOG.error("Could not update linked account {}", account, e);
                output = e;
                resultStatus = Result.FAILURE;
            }

            finalize(MatchingRule.toEventName(MatchingRule.UPDATE), resultStatus, before, output, delta);
        }

        return Optional.of(report);
    }

    protected Optional<ProvisioningReport> delete(
            final SyncDelta delta,
            final LinkedAccount account)
            throws JobExecutionException {

        if (!profile.getTask().isPerformDelete()) {
            LOG.debug("PullTask not configured for delete");
            finalize(ResourceOperation.DELETE.name().toLowerCase(), Result.SUCCESS, null, null, delta);
            return Optional.empty();
        }

        LOG.debug("About to delete {}", account);

        Object output;
        Result resultStatus = Result.FAILURE;

        ProvisioningReport report = new ProvisioningReport();

        try {
            report.setKey(account.getKey());
            report.setName(account.getConnObjectKeyValue());
            report.setOperation(ResourceOperation.DELETE);
            report.setAnyType(PullMatch.MatchTarget.LINKED_ACCOUNT.name());
            report.setStatus(ProvisioningReport.Status.SUCCESS);

            if (!profile.isDryRun()) {
                LinkedAccountTO before = userDataBinder.getLinkedAccountTO(account);

                for (PullActions action : profile.getActions()) {
                    action.beforeDelete(profile, delta, before);
                }

                UserPatch patch = new UserPatch();
                patch.setKey(account.getOwner().getKey());
                patch.getLinkedAccounts().add(new LinkedAccountPatch.Builder().
                        operation(PatchOperation.DELETE).linkedAccountTO(before).build());

                try {
                    userProvisioningManager.update(
                            patch,
                            report,
                            null,
                            Collections.singleton(profile.getTask().getResource().getKey()),
                            true);
                    resultStatus = Result.SUCCESS;

                    output = null;

                    for (PullActions action : profile.getActions()) {
                        action.after(profile, delta, before, report);
                    }
                } catch (Exception e) {
                    throwIgnoreProvisionException(delta, e);

                    report.setStatus(ProvisioningReport.Status.FAILURE);
                    report.setMessage(ExceptionUtils.getRootCauseMessage(e));
                    LOG.error("Could not delete linked account {}", account, e);
                    output = e;
                }

                finalize(ResourceOperation.DELETE.name().toLowerCase(), resultStatus, before, output, delta);
            }
        } catch (Exception e) {
            LOG.error("Could not delete linked account {}", account, e);
        }

        return Optional.of(report);
    }

    protected ProvisioningReport ignore(
            final SyncDelta delta,
            final LinkedAccount account,
            final boolean matching,
            final String... message)
            throws JobExecutionException {

        LOG.debug("Linked account to ignore {}", delta.getObject().getUid().getUidValue());

        ProvisioningReport report = new ProvisioningReport();
        if (account == null) {
            report.setKey(null);
            report.setName(delta.getObject().getUid().getUidValue());
            report.setOperation(ResourceOperation.NONE);
            report.setAnyType(PullMatch.MatchTarget.LINKED_ACCOUNT.name());
            report.setStatus(ProvisioningReport.Status.SUCCESS);
            if (message != null && message.length >= 1) {
                report.setMessage(message[0]);
            }
        } else {
            report.setKey(account.getKey());
            report.setName(delta.getObject().getUid().getUidValue());
            report.setOperation(ResourceOperation.NONE);
            report.setAnyType(PullMatch.MatchTarget.LINKED_ACCOUNT.name());
            report.setStatus(ProvisioningReport.Status.SUCCESS);
            if (message != null && message.length >= 1) {
                report.setMessage(message[0]);
            }
        }

        finalize(matching
                ? MatchingRule.toEventName(MatchingRule.IGNORE)
                : UnmatchingRule.toEventName(UnmatchingRule.IGNORE), AuditElements.Result.SUCCESS, null, null, delta);

        return report;
    }
}
