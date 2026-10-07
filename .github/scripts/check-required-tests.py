#!/usr/bin/env python3
"""Require every focused plugin regression to run and pass in Gradle JUnit XML.

Run after ``cleanTest test --no-build-cache`` so reports come from the current run.
"""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


PACKAGE = "com.github.biomejs.intellijbiome."
REQUIRED_TESTS = {
    PACKAGE + "BiomeConfigTest": {
        "testClosingCancellationAfterExpectedReadFailureIsPreserved",
        "testClosingFatalFailureAfterExpectedReadFailureIsPreserved",
        "testCoroutineCancellationIdentityIsPreserved",
        "testFatalFailureIdentityIsPreserved",
        "testIoFailureClosingReturnsNullAndClosesStreamOnce",
        "testIoFailureOpeningReturnsNull",
        "testIoFailureReadingReturnsNullAndClosesStreamOnce",
        "testMalformedInputReturnsNullAndClosesStreamOnce",
        "testPlatformCancellationIdentityIsPreserved",
        "testPlatformControlFlowIdentityIsPreserved",
        "testRootAndExtendsSemanticsArePreserved",
        "testUnexpectedRuntimeFailureIdentityIsPreserved",
        "testValidJsonClosesStreamOnce",
        "testValidJsoncClosesStreamOnce",
    },
    PACKAGE + "actions.BiomeCheckOnSaveActionTest": {
        "testDisabledPreferencesExecuteNoSaveWork",
        "testFeatureSnapshotIsStable",
        "testFileSpecificFailureFeedback",
        "testIneligibleDocumentsAreSkipped",
        "testPlatformCancellationPropagates",
    },
    PACKAGE + "actions.BiomeSaveOperationTest": {
        "testCancellationExceptionFromOperationIsPreserved",
        "testCompletionAndFailure",
        "testFeatureStagesShareOneBudget",
        "testOuterTimeoutPropagates",
        "testOwnTimeoutIsRecoverable",
        "testParentCancellationPropagates",
        "testPlatformCancellationPropagates",
        "testPlatformControlFlowPropagates",
    },
    PACKAGE + "launcher.BiomeLauncherLspTest": {
        "testVersion1DescriptorPreservesSelectedLauncherAndConfig",
        "testVersion2DescriptorPreservesSelectedLauncherAndConfig",
    },
    PACKAGE + "launcher.BiomeLauncherTest": {
        "testCancellingNodeLauncherCollectionTerminatesItsProcess",
        "testEnvironmentSettingShebangIsNotStripped",
        "testManualLauncherKeepsSelectedTarget",
        "testNativeDoesNotRequireConfiguredNodeInterpreter",
        "testNativeWithJavaScriptSuffixStillRunsDirectly",
        "testNodeScriptPreservesWorkingDirectoryArgumentsAndEnvironment",
        "testNpmLauncherPreservesBiomeBinaryOverride",
        "testShellWrapperKeepsItsEnvironmentSetup",
        "testVersion1NpmLauncherUsesConfiguredInterpreterWithoutPathNode",
        "testVersion2NpmLauncherUsesConfiguredInterpreterWithoutPathNode",
    },
    PACKAGE + "lsp.BiomeConfigRecoveryLspTest": {
        "testCopiedConfigStartsForAlreadyOpenFile",
        "testDeleteAndRecreateConfigStartsOnce",
        "testDisabledPluginDoesNotStartAfterConfigCreation",
        "testExistingServerObservesConfigEditWithoutRestart",
        "testExistingValidConfigStartsAndFormats",
        "testExternalConfigCreationStartsForAlreadyOpenFile",
        "testMalformedConfigRepairStartsForAlreadyOpenFile",
        "testProjectDisposalPreventsLateServerStartAfterConfigEvent",
        "testRecoveryPreservesAnotherProjectServer",
        "testRecoveryPreservesUnrelatedRootServer",
        "testRenameConfigStartsForAlreadyOpenFile",
        "testReopenAfterExternalConfigCreationIsRecoveryControl",
        "testStillMalformedConfigDoesNotStart",
        "testUnsupportedFileDoesNotStartAfterConfigCreation",
    },
    PACKAGE + "lsp.BiomeDependencyRefreshLifecycleTest": {
        "testBrokenProspectivePackagePreservesServerDuringOriginalUpgrade",
        "testChangedPrereleaseIsAdoptedOnce",
        "testClosedFailedRootDoesNotBlockHealthyRootUpgrade",
        "testClosedParentDoesNotBlockNestedRootUpgrade",
        "testClosedStartupFileRetainsSameRootPackageSelection",
        "testDeletedStartupFileRetainsPackageDiscoveryContext",
        "testInterpreterChangeDuringProbePreservesWorkingServer",
        "testMalformedOpenRootWithNonRootFallbackStillBlocksUnsafeRestart",
        "testNestedPackageWithinOneConfigRootKeepsFileSpecificSelection",
        "testReopenedIdleRootIsVerifiedBeforeProjectRestart",
        "testSupersedingInstallEventInvalidatesSuccessfulOlderProbeImmediately",
        "testTwoPackagesInOneRootDoNotChangeStartupSelection",
        "testUnchangedNestedRootVersionsKeepBothServers",
        "testUnchangedPrereleaseDoesNotRestart",
    },
    PACKAGE + "lsp.BiomeDependencyUpgradeLspTest": {
        "testClosedRootDoesNotBlockRemainingRootsUpgrade",
        "testDependencyEventBurstAdoptsVersionBOnce",
        "testDependencyRenameAndRecreationPreservesOldServerUntilValid",
        "testDependencyUpgradePreservesAnotherProjectsServer",
        "testDisabledModeDoesNotRefreshAfterDependencyUpgrade",
        "testExistingRestartAdoptsInstalledVersionB",
        "testExistingRestartKeepsAnotherProjectResponsiveWithoutAnInstall",
        "testFailedReplacementKeepsWorkingServerThenRecovers",
        "testManualModeDoesNotRefreshAfterDependencyUpgrade",
        "testParentMonorepoLockAdoptsReplacementWithoutPackageRefresh",
        "testProjectDisposalCancelsPendingDependencyRefresh",
        "testProjectRestartRetainsSecondRootsOwnBinaryAndConfiguration",
        "testRealInstallAdoptsVersionBWithoutRestart",
        "testTemporarilyMissingInterpreterDoesNotLoseRefreshSubscription",
        "testUpgradeWhileAnotherRootInitializesIsRetriedWhenItRuns",
    },
    PACKAGE + "lsp.BiomeDiagnosticsTest": {
        "testAbsentCodeRendersMessageAndTooltip",
        "testIntegerAndZeroCodesRenderMessageAndTooltip",
        "testMultilineAndHtmlSensitiveTextIsEscapedOnlyInTooltip",
        "testRuntimeMessageRepresentationsKeepTheirText",
        "testStringCodeRendersMessageAndTooltip",
    },
    PACKAGE + "lsp.BiomeLanguageLspTest": {
        "testArbitraryXmlPreservesNativeIdeFormatting",
        "testDefaultGritUsesGritIdentityAndFormats",
        "testDefaultSvgPreservesNativeIdeFormatting",
        "testDisabledPluginSavesGritWithoutStartingOrFormatting",
        "testExplicitSvgReturnsNoEditsWithHtmlDisabled",
        "testExplicitSvgUsesSdkIdentityAndReturnsNoEditsWithHtmlEnabled",
        "testGritFormatsAndPersistsThroughActualSave",
    },
    PACKAGE + "lsp.BiomeLanguageRoutingTest": {
        "testArbitraryXmlIsNotSupportedByDefault",
        "testBaselineSdkUsesGritSuffixForLspIdentity",
        "testBaselineSdkUsesSvgSuffixForLspIdentity",
        "testDisabledPluginDisablesFormatting",
        "testExplicitSvgExtensionRemainsSupported",
        "testGritIsSupportedByDefault",
        "testPersistedCustomExtensionsAreNotReplacedByNewDefaults",
        "testResetToDefaultsLinkAppliesGritWithoutClaimingSvg",
        "testSvgIsNotClaimedUntilOlderServerFallbackIsVerified",
    },
    PACKAGE + "lsp.BiomeManualConfigCliTest": {
        "testVersion1SelectionContract",
        "testVersion2SelectionContract",
    },
    PACKAGE + "lsp.BiomeManualConfigLspTest": {
        "testLegacyDirectoryUsesDoubleQuotes",
        "testSelectedJsonUsesDoubleQuotes",
        "testSelectedJsoncUsesSingleQuotes",
    },
    PACKAGE + "lsp.BiomeManualConfigRecoveryLspTest": {
        "testDisabledModePreventsPendingManualRecovery",
        "testDisposalCancelsPendingManualConfigRecovery",
        "testExplicitOverrideAddedAfterOpenPreventsDiscoveryRecovery",
        "testExplicitOverrideKeepsSelectedConfigAfterUnrelatedConfigCreation",
        "testMalformedConfigRepairRecoversSameEditorWithSelectedExecutable",
        "testManualRecoveryPreservesAnotherProjectServerAndFormatting",
        "testMissingConfigCreationRecoversSameEditorWithSelectedExecutable",
        "testUnrelatedConfigDoesNotRecoverManualEditor",
        "testWhitespaceOverrideUsesSameEditorDiscovery",
    },
    PACKAGE + "lsp.BiomeManualConfigV1LspTest": {
        "testVersion1EmptyOverrideOmitsConfigArgument",
        "testVersion1LaunchPreservesSelectedJsonc",
        "testVersion1LegacyDirectoryLaunch",
    },
    PACKAGE + "lsp.BiomeNestedRootsLspTest": {
        "testClosedEditorInvalidatesQueuedNestedRecovery",
        "testMalformedConfigInvalidatesQueuedNestedRecovery",
        "testChildFirstPublicRestartRestoresBothWorkspaces",
        "testChildFirstRepairAfterMalformedRestartRestoresIndependentWorkspace",
        "testChildThenParentIdeFormattingUsesChildServer",
        "testChildThenParentServiceFormattingUsesChildServer",
        "testDisabledModeDoesNotRecoverNestedConfig",
        "testExplicitManualConfigRetainsProjectWideOwnership",
        "testMalformedEstablishedChildKeepsExclusiveOwnership",
        "testMalformedExistingRootDoesNotPermanentlyRejectNewFile",
        "testManualModeDoesNotRecoverNestedConfig",
        "testMissingExistingRootRetainsNonRootChildOwnership",
        "testNestedNonRootConfigRemainsInParentWorkspace",
        "testNestedRepairPreservesAnotherProjectServer",
        "testNewIndependentChildConfigRecoversUnownedEditor",
        "testParentFirstPublicRestartRestoresBothWorkspaces",
        "testParentThenChildIdeFormattingUsesChildServer",
        "testParentThenChildServiceFormattingUsesChildServer",
        "testRepairAfterDependencyRefreshRestoresIndependentWorkspace",
        "testRepairAfterMalformedChildRestartRestoresIndependentWorkspace",
        "testRepairWithoutRestartControlRestoresIndependentWorkspace",
    },
    PACKAGE + "lsp.BiomeSaveActionsTest": {
        "testCancellationBeforeWriteDoesNotMutate",
        "testCancellationWhileWriteIsQueuedDoesNotMutate",
        "testCrLfOnlyEditPersists",
        "testDocumentSaveDetectsExternalDiskWrite",
        "testEnabledFeaturesRunInOrderAndPersist",
        "testIdeFormatOnSaveOrderingAndUndo",
        "testLfOnlyEditPersists",
        "testMissingServerIsNoOp",
        "testOrganizeImportsPersistsRealServerEdits",
        "testPartialSuccessPersistsAfterTimeout",
        "testPlatformCancellationPropagates",
        "testProjectDisposalCancelsPendingSave",
        "testSaveAllContinuesAfterFileFailure",
        "testSaveAllContinuesAfterFileTimeout",
        "testSavePreservesCrLfBytes",
        "testSavePreservesLfBytes",
        "testSeparatorOnlyFormattingPreservesExternalDiskWrite",
        "testSeparatorPersistsWhenEarlierEditsReturnToSavedText",
        "testSeparatorRedoPreservesExternalDiskWrite",
        "testSeparatorUndoPreservesExternalDiskWrite",
        "testTextAndCrLfFormattingUndoRedoRestoresBytes",
        "testTextAndLfFormattingUndoRedoRestoresBytes",
        "testTypingDiscardsStaleResponseAndRemainingFeatures",
        "testTypingDuringSaveCancelsOnlyThatDocument",
    },
    PACKAGE + "lsp.BiomeSharedDaemonLspTest": {
        "testNativeRestartPreservesSharedDaemonAndCleansUpProxies",
        "testNodeRestartPreservesSharedDaemonAndCleansUpProxies",
    },
    PACKAGE + "lsp.OlderBiomeLanguageLspTest": {
        "testDefaultGritFormatsAfterServerInitialization",
        "testExplicitSvgReturnsNoEditsOnOlderBiome",
    },
    PACKAGE + "lsp.UnusedFunctionHighlightingTest": {
        "testUnusedFunctionDiagnosticsProduceSnapshotDiagnostics",
        "testDiagnosticQuickFixRenamesUnusedParameter",
    },
    PACKAGE + "lsp.V1BiomeLanguageLspTest": {
        "testJavascriptStillFormatsWithV1",
        "testUnsupportedGritRemainsUnchangedWithV1",
    },
    PACKAGE + "settings.BiomeDisabledPreferencesTest": {
        "testActionsOnSaveResetAndApplyPreserveDisabledPreferences",
        "testCancelDoesNotChangePreferencesOrMode",
        "testDisableApplyReopenEnablePreservesPreferences",
        "testDisabledSerializationPreservesPreferences",
        "testInitiallyDisabledApplyPreservesPreferences",
    },
    PACKAGE + "settings.BiomeManualConfigSettingsTest": {
        "testBlankOverrideRoundTrip",
        "testConfigPathValidation",
        "testHiddenManualInputDoesNotBlockModeChange",
        "testInvalidManualInputCannotApply",
        "testLegacyDirectoryRoundTrip",
        "testPathWithSpaces",
        "testSelectedConfigSurvivesApplyAndReopen",
        "testSelectedFilesRoundTrip",
    },
    PACKAGE + "startup.BiomeStartupLspTest": {
        "testAutomaticRootsProbeTheirSelectedDependencyInsteadOfPackageMetadata",
        "testDisabledPluginDoesNotProbeOrRequestServerStart",
        "testManualV1AndV2ConfigurationTransportIsPreserved",
        "testStaleDescriptorNeverStartsAProbeOrServer",
        "testStopDuringFinalProcessCreationDoesNotLoseProcessOwnership",
        "testStoppingInitializingServerCancelsProbeAndPreventsLaunch",
        "testSupportedFileRequestsServerStart",
        "testTwoNestedRootsKeepSelectedExecutableAndWorkingDirectory",
        "testUnsupportedFileDoesNotProbeOrRequestServerStart",
    },
    PACKAGE + "startup.BiomeStartupProbeTest": {
        "testCancellingCollectionTerminatesWrapperDescendants",
        "testCancellingNodeStyleCollectionTerminatesInterruptIgnoringDescendants",
        "testCancellingVersionCollectionTerminatesTheProcess",
        "testCoroutineCancellationTerminatesTheProcess",
        "testInvalidVersionAndNonzeroExitRemainFailures",
        "testMissingExecutableRemainsAFailure",
        "testNonzeroExitIsPreserved",
        "testPlatformCancellationIsPreservedAndTerminatesChild",
        "testProjectDisposalTerminatesTheChild",
        "testVersionCoroutineCancellationIsPreservedAndTerminatesChild",
        "testVersionDeadlineTerminatesTheProcess",
        "testVersionOutputIdentifiesV1AndV2",
    },
}


def check_reports(report_directory):
    reports = sorted(report_directory.glob("TEST-*.xml"))
    if not reports:
        return [f"No JUnit reports found in {report_directory}"]

    problems = []
    seen_classes = set()
    for report in reports:
        try:
            suite = ET.parse(report).getroot()
        except (ET.ParseError, OSError) as error:
            problems.append(f"Cannot parse {report}: {error}")
            continue
        if suite.tag != "testsuite":
            problems.append(f"Expected testsuite root in {report}")
            continue

        class_name = suite.get("name")
        if class_name not in REQUIRED_TESTS:
            continue
        if class_name in seen_classes:
            problems.append(f"Duplicate report for {class_name}: {report}")
        seen_classes.add(class_name)

        counts = {}
        for attribute in ("tests", "failures", "errors", "skipped"):
            value = suite.get(attribute)
            try:
                count = int(value)
                if count < 0:
                    raise ValueError
            except (TypeError, ValueError):
                problems.append(f"Invalid {attribute} count {value!r} in {report}")
                continue
            counts[attribute] = count
            if attribute != "tests" and count:
                problems.append(f"{class_name} reports {attribute}={count}")

        cases = suite.findall("testcase")
        if counts.get("tests") != len(cases):
            problems.append(f"Test count does not match {len(cases)} testcases in {report}")
        executed = 0
        seen_methods = set()
        for case in cases:
            method = case.get("name")
            if case.get("classname") != class_name:
                problems.append(f"Unexpected testcase class for {class_name}.{method}")
                continue
            if method in seen_methods:
                problems.append(f"Duplicate method {class_name}.{method}")
            seen_methods.add(method)
            for outcome in ("failure", "error", "skipped"):
                if case.find(outcome) is not None:
                    problems.append(f"{class_name}.{method} contains {outcome}")
            if case.find("skipped") is None:
                executed += 1

        if not executed:
            problems.append(f"No executed tests in {class_name}")
        for method in sorted(REQUIRED_TESTS[class_name] - seen_methods):
            problems.append(f"Missing required method {class_name}.{method}")

    for class_name in sorted(REQUIRED_TESTS.keys() - seen_classes):
        problems.append(f"Missing required class {class_name}")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report_directory", type=Path)
    args = parser.parse_args()
    problems = check_reports(args.report_directory)
    if problems:
        print("Required plugin regression checks failed:", file=sys.stderr)
        for problem in problems:
            print(f"- {problem}", file=sys.stderr)
        return 1
    required_count = sum(len(methods) for methods in REQUIRED_TESTS.values())
    print(f"Verified {required_count} required tests across {len(REQUIRED_TESTS)} classes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
