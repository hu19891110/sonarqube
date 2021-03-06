/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package it.qualityGate;

import com.sonar.orchestrator.Orchestrator;
import com.sonar.orchestrator.locator.FileLocation;
import it.Category1Suite;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.sonar.wsclient.qualitygate.QualityGateClient;
import org.sonar.wsclient.services.Measure;
import org.sonar.wsclient.services.Resource;
import org.sonar.wsclient.services.ResourceQuery;
import org.sonarqube.ws.client.WsClient;
import org.sonarqube.ws.client.qualitygate.CreateConditionRequest;
import org.sonarqube.ws.client.qualitygate.QualityGatesService;
import org.sonarqube.ws.client.qualitygate.SelectWsRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static util.ItUtils.newAdminWsClient;
import static util.ItUtils.resetSettings;
import static util.ItUtils.runProjectAnalysis;
import static util.ItUtils.setServerProperty;

public class QualityGateOnRatingMeasuresTest {

  @ClassRule
  public static Orchestrator orchestrator = Category1Suite.ORCHESTRATOR;

  private static final String PROJECT_KEY = "sample";

  static WsClient wsClient;

  static QualityGatesService QUALITY_GATES;

  Long qualityGateId;

  @BeforeClass
  public static void init() {
    wsClient = newAdminWsClient(orchestrator);
    QUALITY_GATES = wsClient.qualityGates();
  }

  @Before
  public void prepareData() {
    orchestrator.resetData();
    qualityGateId = QUALITY_GATES.create("QualityGate").getId();
    orchestrator.getServer().provisionProject(PROJECT_KEY, PROJECT_KEY);
    QUALITY_GATES.associateProject(new SelectWsRequest().setGateId(qualityGateId).setProjectKey(PROJECT_KEY));
  }

  @After
  public void resetData() throws Exception {
    qgClient().destroy(qualityGateId);
    resetSettings(orchestrator, null, "sonar.timemachine.period1");
  }

  @Test
  public void generate_warning_qgate_on_rating_metric() throws Exception {
    QUALITY_GATES.createCondition(CreateConditionRequest.builder()
      .setQualityGateId(qualityGateId.intValue())
      .setMetricKey("security_rating")
      .setOperator("GT")
      .setWarning("3")
      .build());
    orchestrator.getServer().restoreProfile(FileLocation.ofClasspath("/qualityGate/QualityGateOnRatingMeasuresTest/with-many-rules.xml"));
    orchestrator.getServer().associateProjectToQualityProfile(PROJECT_KEY, "xoo", "with-many-rules");

    runProjectAnalysis(orchestrator, "qualitygate/xoo-sample");

    assertThat(fetchGateStatus().getData()).isEqualTo("WARN");
  }

  @Test
  public void generate_error_qgate_on_rating_metric_on_leak_period() throws Exception {
    setServerProperty(orchestrator, "sonar.timemachine.period1", "previous_analysis");
    QUALITY_GATES.createCondition(CreateConditionRequest.builder()
      .setQualityGateId(qualityGateId.intValue())
      .setMetricKey("security_rating")
      .setOperator("GT")
      .setError("3")
      .setPeriod(1)
      .build());

    // Run first analysis with empty quality gate -> quality gate is green
    orchestrator.getServer().associateProjectToQualityProfile(PROJECT_KEY, "xoo", "empty");
    runProjectAnalysis(orchestrator, "qualitygate/xoo-sample");
    assertThat(fetchGateStatus().getData()).isEqualTo("OK");

    // Run second analysis with some rules that makes Security Rating to E -> quality gate is red
    orchestrator.getServer().restoreProfile(FileLocation.ofClasspath("/qualityGate/QualityGateOnRatingMeasuresTest/with-many-rules.xml"));
    orchestrator.getServer().associateProjectToQualityProfile(PROJECT_KEY, "xoo", "with-many-rules");
    runProjectAnalysis(orchestrator, "qualitygate/xoo-sample");
    assertThat(fetchGateStatus().getData()).isEqualTo("ERROR");
  }

  private Measure fetchGateStatus() {
    return fetchResourceWithGateStatus().getMeasure("alert_status");
  }

  private Resource fetchResourceWithGateStatus() {
    return orchestrator.getServer().getWsClient().find(ResourceQuery.createForMetrics(PROJECT_KEY, "alert_status").setIncludeAlerts(true));
  }

  private static QualityGateClient qgClient() {
    return orchestrator.getServer().adminWsClient().qualityGateClient();
  }

}
