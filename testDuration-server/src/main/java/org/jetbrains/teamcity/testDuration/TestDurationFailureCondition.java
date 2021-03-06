package org.jetbrains.teamcity.testDuration;

import jetbrains.buildServer.BuildProblemData;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.tests.TestName;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.vcs.SelectPrevBuildPolicy;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Pattern;

public class TestDurationFailureCondition extends BuildFeature {

  public static final String TYPE = "BuildFailureOnSlowTest";
  public static final String PROBLEM_TYPE = "testDurationFailureCondition";
  public static final String TEST_NAMES_PATTERNS_PARAM = "testNamesPatterns";
  public static final String MIN_DURATION_PARAM = "minDuration";
  public static final String THRESHOLD_PARAM = "threshold";

  private final BuildHistory myBuildHistory;
  private final PluginDescriptor myPluginDescriptor;

  public TestDurationFailureCondition(@NotNull BuildHistory buildHistory, @NotNull PluginDescriptor pluginDescriptor) {
    myBuildHistory = buildHistory;
    myPluginDescriptor = pluginDescriptor;
  }

  @NotNull
  @Override
  public String getType() {
    return TYPE;
  }

  @NotNull
  @Override
  public String getDisplayName() {
    return "Fail build if tests duration increases";
  }

  @Nullable
  @Override
  public String getEditParametersUrl() {
    return myPluginDescriptor.getPluginResourcesPath("editFeatureParams.jsp");
  }

  @Override
  public PlaceToShow getPlaceToShow() {
    return PlaceToShow.FAILURE_REASON;
  }

  @Nullable
  @Override
  public Map<String, String> getDefaultParameters() {
    return new HashMap<String, String>() {{
      put(TEST_NAMES_PATTERNS_PARAM, ".*");
      put(MIN_DURATION_PARAM, "1000");
      put(THRESHOLD_PARAM, "80");
    }};
  }

  @Nullable
  @Override
  public PropertiesProcessor getParametersProcessor() {
    return new PropertiesProcessor() {
      @Override
      public Collection<InvalidProperty> process(Map<String, String> properties) {
        List<InvalidProperty> res = new ArrayList<InvalidProperty>();
        if (StringUtil.isEmpty(getTestNamesPatterns(properties))) {
          res.add(new InvalidProperty(TEST_NAMES_PATTERNS_PARAM, "Test names patterns are not specified"));
        }
        if (StringUtil.isEmpty(getMinimumDuration(properties))) {
          res.add(new InvalidProperty(MIN_DURATION_PARAM, "Minimum duration is not specified"));
        }
        if (StringUtil.isEmpty(getThreshold(properties))) {
          res.add(new InvalidProperty(THRESHOLD_PARAM, "Threshold is not specified"));
        }
        return res;
      }
    };
  }

  private String getMinimumDuration(@NotNull  Map<String, String> properties) {
    return properties.get(MIN_DURATION_PARAM);
  }

  private String getThreshold(@NotNull Map<String, String> properties) {
    return properties.get(THRESHOLD_PARAM);
  }

  private String getTestNamesPatterns(@NotNull Map<String, String> properties) {
    return properties.get(TEST_NAMES_PATTERNS_PARAM);
  }

  @NotNull
  @Override
  public String describeParameters(@NotNull Map<String, String> params) {
    StringBuilder sb = new StringBuilder();
    sb.append("Test names patterns: ").append(getTestNamesPatterns(params)).append("<br>");
    sb.append("Threshold: ").append(getThreshold(params)).append("%<br>");
    sb.append("Minimum duration: ").append(getMinimumDuration(params)).append(" ms");
    return sb.toString();
  }

  public void checkBuild(@NotNull SRunningBuild build, @NotNull SBuildFeatureDescriptor featureDescriptor) {
    SBuild etalon = getEtalonBuild(build);
    if (etalon == null)
      return;
    compareTestDurations(getSettings(featureDescriptor), etalon, build);
  }

  @Nullable
  private SBuild getEtalonBuild(@NotNull SRunningBuild build) {
    BuildPromotion p = build.getBuildPromotion().getPreviousBuildPromotion(SelectPrevBuildPolicy.SINCE_LAST_SUCCESSFULLY_FINISHED_BUILD);
    if (p == null)
      return null;
    return p.getAssociatedBuild();
  }

  private void compareTestDurations(@NotNull FailureConditionSettings settings, @NotNull SBuild etalon, @NotNull SRunningBuild build) {
    BuildStatistics etalonStat = etalon.getBuildStatistics(new BuildStatisticsOptions(BuildStatisticsOptions.PASSED_TESTS, 0));
    BuildStatistics stat = build.getBuildStatistics(new BuildStatisticsOptions(BuildStatisticsOptions.PASSED_TESTS, 0));
    List<SFinishedBuild> referenceBuilds = getBuildsBetween(etalon, build);
    processTests(settings, etalon, etalonStat, build, stat, referenceBuilds);
  }

  private void processTests(@NotNull FailureConditionSettings settings,
                            @NotNull SBuild etalon,
                            @NotNull BuildStatistics etalonStat,
                            @NotNull SRunningBuild build,
                            @NotNull BuildStatistics buildStat,
                            @NotNull List<SFinishedBuild> referenceBuilds) {
    Set<String> processedTests = new HashSet<String>();
    for (STestRun run : buildStat.getPassedTests()) {
      TestName testName = run.getTest().getName();
      String name = testName.getAsString();
      if (!settings.isInteresting(name))
        continue;

      if (!processedTests.add(name))
        continue;

      int duration = run.getDuration();
      for (SFinishedBuild referenceBuild : referenceBuilds) {
        BuildStatistics referenceStat = getBuildStat(referenceBuild, etalon, etalonStat);
        List<STestRun> referenceTestRuns = referenceStat.findTestsBy(testName);
        boolean failFound = false;
        for (STestRun referenceRun : referenceTestRuns) {
          if (referenceRun.isIgnored() || referenceRun.isMuted())
            continue;
          int referenceDuration = referenceRun.getDuration();
          if (settings.isSlow(referenceDuration, duration)) {
            int slowdown = (int) ((duration - referenceDuration) * 100.0 / referenceDuration);
            TestSlowdownInfo info = new TestSlowdownInfo(run.getTestRunId(), duration, referenceRun.getTestRunId(), referenceDuration, referenceBuild.getBuildId());
            build.addBuildProblem(BuildProblemData.createBuildProblem("testDurationFailureCondition." + run.getTestRunId(),
                    PROBLEM_TYPE,
                    "Test test '" + name + "' became " + slowdown + "% slower",
                    info.asString()));
          }
        }
        if (failFound)
          break;
      }
    }
  }


  @NotNull
  private BuildStatistics getBuildStat(@NotNull SBuild build, @NotNull SBuild etalonBuild, @NotNull BuildStatistics etalonStat) {
    if (build.equals(etalonBuild))
      return etalonStat;
    return build.getBuildStatistics(new BuildStatisticsOptions(BuildStatisticsOptions.PASSED_TESTS, 0));
  }


  @NotNull
  private List<SFinishedBuild> getBuildsBetween(@NotNull SBuild b1, @NotNull SBuild b2) {
    List<SFinishedBuild> builds = myBuildHistory.getEntriesSince(b1, b1.getBuildType());
    if (builds.isEmpty())
      return builds;
    List<SFinishedBuild> result = new ArrayList<SFinishedBuild>();
    for (SFinishedBuild b : builds) {
      if (b.equals(b2))
        break;
      result.add(b);
    }
    Collections.reverse(result);
    return result;
  }


  @NotNull
  private FailureConditionSettings getSettings(@NotNull SBuildFeatureDescriptor featureDescriptor) {
    int minDuration;
    try {
      minDuration = Integer.valueOf(getMinimumDuration(featureDescriptor.getParameters()));
    } catch (Exception e) {
      minDuration = 300;
    }

    try {
      return new FailureConditionSettingsImpl(Pattern.compile(getTestNamesPatterns(featureDescriptor.getParameters())),
              Double.valueOf(getThreshold(featureDescriptor.getParameters())), minDuration);
    } catch (Exception e) {
      return new EmptyFailureConditionSettings();
    }
  }


  interface FailureConditionSettings {
    boolean isInteresting(@NotNull String testName);
    boolean isSlow(int etalonDuration, int duration);
  }


  private class FailureConditionSettingsImpl implements FailureConditionSettings {
    private final Pattern myTestNamePattern;
    private final double myFailureThreshold;
    private final int myMinDuration;
    private FailureConditionSettingsImpl(@NotNull Pattern testNamePattern, double failureThreshold, int minDuration) {
      myTestNamePattern = testNamePattern;
      myFailureThreshold = failureThreshold;
      myMinDuration = minDuration;
    }

    public boolean isInteresting(@NotNull String testName) {
      return myTestNamePattern.matcher(testName).matches();
    }

    public boolean isSlow(int etalonDuration, int duration) {
      if (duration < myMinDuration)
        return false;
      return (duration - etalonDuration) * 100.0 / etalonDuration > myFailureThreshold;
    }
  }

  private class EmptyFailureConditionSettings implements FailureConditionSettings {
    public boolean isInteresting(@NotNull String testName) {
      return false;
    }
    public boolean isSlow(int etalonDuration, int duration) {
      return false;
    }
  }
}
