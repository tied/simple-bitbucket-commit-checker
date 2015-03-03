package se.bjurr.sscc;

import static com.atlassian.stash.repository.RefChangeType.DELETE;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newTreeMap;
import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static java.util.regex.Pattern.compile;
import static se.bjurr.sscc.settings.SSCCGroup.Accept.ACCEPT;
import static se.bjurr.sscc.settings.SSCCGroup.Accept.SHOW_MESSAGE;
import static se.bjurr.sscc.settings.SSCCGroup.Match.ALL;
import static se.bjurr.sscc.settings.SSCCGroup.Match.NONE;
import static se.bjurr.sscc.settings.SSCCGroup.Match.ONE;
import static se.bjurr.sscc.settings.SSCCSettings.sscSettings;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.bjurr.sscc.data.SSCCChangeSet;
import se.bjurr.sscc.data.SSCCChangeSetVerificationResult;
import se.bjurr.sscc.data.SSCCRefChangeVerificationResult;
import se.bjurr.sscc.data.SSCCVerificationResult;
import se.bjurr.sscc.settings.SSCCGroup;
import se.bjurr.sscc.settings.SSCCMatch;
import se.bjurr.sscc.settings.SSCCRule;
import se.bjurr.sscc.settings.SSCCSettings;

import com.atlassian.stash.hook.HookResponse;
import com.atlassian.stash.hook.repository.PreReceiveRepositoryHook;
import com.atlassian.stash.hook.repository.RepositoryHookContext;
import com.atlassian.stash.repository.RefChange;
import com.atlassian.stash.user.StashAuthenticationContext;
import com.google.common.annotations.VisibleForTesting;

public class SsccPreReceiveRepositoryHook implements PreReceiveRepositoryHook {
 private static final String HOOK_NAME = "Simple Stash Commit Checker";

 private static final Logger logger = LoggerFactory.getLogger(PreReceiveRepositoryHook.class);

 private ChangeSetsService changesetsService;

 private final StashAuthenticationContext stashAuthenticationContext;

 public SsccPreReceiveRepositoryHook(ChangeSetsService changesetsService,
   StashAuthenticationContext stashAuthenticationContext) {
  this.changesetsService = changesetsService;
  this.stashAuthenticationContext = stashAuthenticationContext;
 }

 @Override
 public boolean onReceive(RepositoryHookContext repositoryHookContext, Collection<RefChange> refChanges,
   HookResponse hookResponse) {
  try {
   final SSCCSettings settings = sscSettings(repositoryHookContext.getSettings());
   final SSCCVerificationResult refChangeVerificationResults = validateRefChanges(repositoryHookContext, refChanges,
     settings, hookResponse);

   printVerificationResults(refChanges, refChangeVerificationResults, hookResponse, settings);

   if (settings.isDryRun() && settings.getDryRunMessage().isPresent()) {
    hookResponse.out().println(settings.getDryRunMessage().get());
   }

   if (!settings.isDryRun()) {
    return refChangeVerificationResults.isAccepted();
   }

   return TRUE;
  } catch (final Exception e) {
   final String message = HOOK_NAME + "> Error while validating reference changes. Will allow all of them. \""
     + e.getMessage() + "\"";
   logger.error(message, e);
   hookResponse.out().println(message);
   return TRUE;
  }
 }

 private void printCommit(HookResponse hookResponse, final SSCCChangeSet ssccChangeSet) {
  hookResponse.out().println();
  hookResponse.out().println(
    ssccChangeSet.getId() + " " + ssccChangeSet.getCommitter().getName() + " <"
      + ssccChangeSet.getCommitter().getEmailAddress() + ">");
  hookResponse.out().println(">>> " + ssccChangeSet.getMessage());
 }

 private void printEmailVerification(HookResponse hookResponse, SSCCSettings settings,
   final SSCCRefChangeVerificationResult refChangeVerificationResult, final SSCCChangeSet ssccChangeSet) {
  if (!refChangeVerificationResult.getSsccChangeSets().get(ssccChangeSet).getEmailResult()) {
   hookResponse.out().println(
     "* Stash: '" + this.stashAuthenticationContext.getCurrentUser().getEmailAddress() + "' != Commit: '"
       + ssccChangeSet.getCommitter().getEmailAddress() + "'");
   if (settings.getRequireMatchingAuthorEmailMessage().isPresent()) {
    hookResponse.out().println(settings.getRequireMatchingAuthorEmailMessage().get());
   }
  }
 }

 private void printNameVerification(HookResponse hookResponse, SSCCSettings settings,
   final SSCCRefChangeVerificationResult refChangeVerificationResult, final SSCCChangeSet ssccChangeSet) {
  if (!refChangeVerificationResult.getSsccChangeSets().get(ssccChangeSet).getNameResult()) {
   hookResponse.out().println(
     "* Stash: '" + this.stashAuthenticationContext.getCurrentUser().getName() + "' != Commit: '"
       + ssccChangeSet.getCommitter().getName() + "'");
   if (settings.getRequireMatchingAuthorNameMessage().isPresent()) {
    hookResponse.out().println(settings.getRequireMatchingAuthorNameMessage().get());
   }
  }
 }

 private void printRefChange(HookResponse hookResponse, final RefChange refChange) {
  hookResponse.out().println(
    refChange.getRefId() + " " + refChange.getFromHash().substring(0, 10) + " -> "
      + refChange.getToHash().substring(0, 10));
 }

 private void printVerificationResults(Collection<RefChange> refChanges, SSCCVerificationResult verificationResult,
   HookResponse hookResponse, SSCCSettings settings) {
  if (verificationResult.isAccepted()) {
   if (settings.getAcceptMessage().isPresent()) {
    hookResponse.out().println(settings.getAcceptMessage().get());
   }
  } else {
   if (settings.getRejectMessage().isPresent()) {
    hookResponse.out().println(settings.getRejectMessage().get());
   }
  }

  for (final SSCCRefChangeVerificationResult refChangeVerificationResult : verificationResult.getRefChanges()) {
   if (!refChangeVerificationResult.hasReportables()) {
    continue;
   }
   printRefChange(hookResponse, refChangeVerificationResult.getRefChange());
   for (final SSCCChangeSet ssccChangeSet : refChangeVerificationResult.getSsccChangeSets().keySet()) {
    SSCCChangeSetVerificationResult changeSetVerificationResult = refChangeVerificationResult.getSsccChangeSets().get(
      ssccChangeSet);
    if (!changeSetVerificationResult.hasReportables()) {
     continue;
    }
    printCommit(hookResponse, ssccChangeSet);
    printEmailVerification(hookResponse, settings, refChangeVerificationResult, ssccChangeSet);
    printNameVerification(hookResponse, settings, refChangeVerificationResult, ssccChangeSet);
    for (final SSCCGroup ssccVerificationResult : changeSetVerificationResult.getGroupsResult().keySet()) {
     showAcceptMessages(hookResponse, ssccVerificationResult);
     showRuleMessage(hookResponse, ssccVerificationResult);
    }
   }
  }
  hookResponse.out().println();
 }

 @VisibleForTesting
 public void setChangesetsService(ChangeSetsService changesetsService) {
  this.changesetsService = changesetsService;
 }

 private void showAcceptMessages(HookResponse hookResponse, final SSCCGroup ssccVerificationResult) {
  if (ssccVerificationResult.getAccept().equals(ACCEPT)) {
   if (ssccVerificationResult.getMessage().isPresent()) {
    hookResponse.out().println(ssccVerificationResult.getMessage().get());
   }
   for (final SSCCRule ssccRule : ssccVerificationResult.getRules()) {
    if (ssccRule.getMessage().isPresent()) {
     hookResponse.out().println("* " + ssccRule.getRegexp() + "\n" + "  " + ssccRule.getMessage().get());
    }
   }
  }
 }

 private void showRuleMessage(HookResponse hookResponse, final SSCCGroup ssccVerificationResult) {
  if (ssccVerificationResult.getAccept().equals(SHOW_MESSAGE)) {
   if (ssccVerificationResult.getMessage().isPresent()) {
    hookResponse.out().println(ssccVerificationResult.getMessage().get());
   }
  }
 }

 private boolean validateChangeSetForEmail(SSCCSettings settings, SSCCChangeSet ssccChangeSet) {
  if (!settings.shouldRequireMatchingAuthorEmail()) {
   return TRUE;
  }
  if (stashAuthenticationContext.getCurrentUser().getEmailAddress()
    .equals(ssccChangeSet.getCommitter().getEmailAddress())) {
   return TRUE;
  }
  return FALSE;
 }

 private Map<SSCCGroup, SSCCMatch> validateChangeSetForGroups(SSCCSettings settings, final SSCCChangeSet ssccChangeSet) {
  final Map<SSCCGroup, SSCCMatch> allMatching = newTreeMap();
  if (ssccChangeSet.getParentCount() > 1 && settings.shouldExcludeMergeCommits()) {
   return allMatching;
  }
  for (final SSCCGroup group : settings.getGroups()) {
   final List<SSCCRule> matchingRules = newArrayList();
   for (final SSCCRule rule : group.getRules()) {
    if (compile(rule.getRegexp()).matcher(ssccChangeSet.getMessage()).find()) {
     matchingRules.add(rule);
    }
   }

   if (group.getAccept().equals(SHOW_MESSAGE)) {
    if (group.getMatch().equals(ALL) && matchingRules.size() == group.getRules().size()) {
     allMatching.put(group, new SSCCMatch(ALL, matchingRules));
    } else if (group.getMatch().equals(NONE) && matchingRules.isEmpty()) {
     allMatching.put(group, new SSCCMatch(NONE, matchingRules));
    } else if (group.getMatch().equals(ONE) && matchingRules.size() >= 1) {
     allMatching.put(group, new SSCCMatch(ONE, matchingRules));
    }
   }

   if (group.getAccept().equals(ACCEPT)) {
    if (group.getMatch().equals(ALL) && matchingRules.size() != group.getRules().size()) {
     allMatching.put(group, new SSCCMatch(ALL, matchingRules));
    } else if (group.getMatch().equals(NONE) && !matchingRules.isEmpty()) {
     allMatching.put(group, new SSCCMatch(NONE, matchingRules));
    } else if (group.getMatch().equals(ONE) && matchingRules.size() == 0) {
     allMatching.put(group, new SSCCMatch(ONE, matchingRules));
    }
   }
  }
  return allMatching;
 }

 private boolean validateChangeSetForName(SSCCSettings settings, SSCCChangeSet ssccChangeSet) {
  if (!settings.shouldRequireMatchingAuthorName()) {
   return TRUE;
  }
  if (stashAuthenticationContext.getCurrentUser().getName().equals(ssccChangeSet.getCommitter().getName())) {
   return TRUE;
  }
  return FALSE;
 }

 private SSCCRefChangeVerificationResult validateRefChange(RefChange refChange, List<SSCCChangeSet> ssccChangeSets,
   SSCCSettings settings, HookResponse hookResponse) throws IOException {
  final SSCCRefChangeVerificationResult refChangeVerificationResult = new SSCCRefChangeVerificationResult(refChange);
  for (final SSCCChangeSet ssccChangeSet : ssccChangeSets) {
   logger.info("ssccChangeSet " + ssccChangeSet.getId() + " " + ssccChangeSet.getMessage() + " "
     + ssccChangeSet.getParentCount());
   logger.info("ssccChangeSet committer " + ssccChangeSet.getCommitter().getEmailAddress() + " "
     + ssccChangeSet.getCommitter().getName());
   refChangeVerificationResult.setGroupsResult(ssccChangeSet, validateChangeSetForGroups(settings, ssccChangeSet));
   refChangeVerificationResult.addEmailValidationResult(ssccChangeSet,
     validateChangeSetForEmail(settings, ssccChangeSet));
   refChangeVerificationResult
     .addNameValidationResult(ssccChangeSet, validateChangeSetForName(settings, ssccChangeSet));
  }
  return refChangeVerificationResult;
 }

 private SSCCVerificationResult validateRefChanges(RepositoryHookContext repositoryHookContext,
   Collection<RefChange> refChanges, SSCCSettings settings, HookResponse hookResponse) throws IOException {
  final SSCCVerificationResult refChangeVerificationResult = new SSCCVerificationResult();
  for (final RefChange refChange : refChanges) {
   logger.info("refChange " + refChange.getFromHash() + " " + refChange.getRefId() + " " + refChange.getToHash() + " "
     + refChange.getType());
   if (compile(settings.getBranches().or(".*")).matcher(refChange.getRefId()).find()) {
    if (refChange.getType() != DELETE) {
     List<SSCCChangeSet> refChangeSets = changesetsService.getNewChangeSets(settings,
       repositoryHookContext.getRepository(), refChange);
     final SSCCRefChangeVerificationResult refChangeVerificationResults = validateRefChange(refChange, refChangeSets,
       settings, hookResponse);
     if (!refChangeVerificationResults.isEmpty()) {
      refChangeVerificationResult.add(refChangeVerificationResults);
     }
    }
   }
  }
  return refChangeVerificationResult;
 }
}