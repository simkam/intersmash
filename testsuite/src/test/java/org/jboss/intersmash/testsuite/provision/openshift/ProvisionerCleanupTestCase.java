/**
 * Copyright (C) 2023 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.intersmash.testsuite.provision.openshift;

import java.util.stream.Stream;

import org.jboss.intersmash.testsuite.IntersmashTestsuiteProperties;
import org.jboss.intersmash.testsuite.junit5.categories.NotForCommunityExecutionProfile;
import org.jboss.intersmash.tools.provision.openshift.Eap7LegacyS2iBuildTemplateProvisioner;
import org.jboss.intersmash.tools.provision.openshift.MysqlImageOpenShiftProvisioner;
import org.jboss.intersmash.tools.provision.openshift.OpenShiftProvisioner;
import org.jboss.intersmash.tools.provision.openshift.PostgreSQLImageOpenShiftProvisioner;
import org.jboss.intersmash.tools.provision.openshift.PostgreSQLTemplateOpenShiftProvisioner;
import org.jboss.intersmash.tools.provision.openshift.RhSsoTemplateOpenShiftProvisioner;
import org.jboss.intersmash.tools.provision.openshift.WildflyBootableJarImageOpenShiftProvisioner;
import org.jboss.intersmash.tools.provision.openshift.WildflyImageOpenShiftProvisioner;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import cz.xtf.core.openshift.OpenShift;
import cz.xtf.core.openshift.OpenShifts;
import cz.xtf.junit5.annotations.CleanBeforeEach;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;

@CleanBeforeEach
public class ProvisionerCleanupTestCase {
	protected static final OpenShift openShift = OpenShifts.master();

	private static Stream<OpenShiftProvisioner> provisionerProvider() {
		if (IntersmashTestsuiteProperties.isCommunityTestExecutionProfileEnabled()) {
			return Stream.of(
					new WildflyBootableJarImageOpenShiftProvisioner(
							OpenShiftProvisionerTestBase.getWildflyBootableJarOpenShiftApplication()),
					new WildflyBootableJarImageOpenShiftProvisioner(
							OpenShiftProvisionerTestBase.getWildflyBootableJarJavaxOpenShiftApplication()),
					new MysqlImageOpenShiftProvisioner(OpenShiftProvisionerTestBase.getMysqlOpenShiftApplication()),
					new PostgreSQLImageOpenShiftProvisioner(
							OpenShiftProvisionerTestBase.getPostgreSQLImageOpenShiftApplication()),
					new PostgreSQLTemplateOpenShiftProvisioner(
							OpenShiftProvisionerTestBase.getPostgreSQLTemplateOpenShiftApplication()));
		} else if (IntersmashTestsuiteProperties.isProductizedTestExecutionProfileEnabled()) {
			return Stream.of(
					new WildflyImageOpenShiftProvisioner(
							OpenShiftProvisionerTestBase.getWildflyOpenShiftLocalBinaryTargetServerApplication()),
					new RhSsoTemplateOpenShiftProvisioner(OpenShiftProvisionerTestBase.getHttpsRhSso()));
		} else {
			throw new IllegalStateException(
					String.format("Unknown Intersmash test suite execution profile: %s",
							IntersmashTestsuiteProperties.getTestExecutionProfile()));
		}
	}

	@ParameterizedTest(name = "{displayName}#class({0})")
	@MethodSource("provisionerProvider")
	public void testProvisioningWorkflowCleanup(OpenShiftProvisioner provisioner) {
		provisioner.configure();
		try {
			provisioner.preDeploy();
			try {
				provisioner.deploy();
				try {
					openShift.configMaps()
							.create(new ConfigMapBuilder().withNewMetadata().withName("no-delete").endMetadata().build());
				} finally {
					provisioner.undeploy();
				}
			} finally {
				provisioner.postUndeploy();
			}
		} finally {
			provisioner.dismiss();
		}
		Assertions.assertNotNull(openShift.configMaps().withName("no-delete").get());
		openShift.configMaps().withName("no-delete").delete();
		openShift.waiters().isProjectClean().waitFor();
	}

	/**
	 * EapS2iBuild application requires additional image streams to be created. CleanBeforeEach would delete it if the
	 * provisioner is initialized in {@link #provisionerProvider()}, so we need a separate test method.
	 */
	@Test
	@NotForCommunityExecutionProfile
	public void eap7LegacyS2iBuild() {
		Eap7LegacyS2iBuildTemplateProvisioner provisioner = new Eap7LegacyS2iBuildTemplateProvisioner(
				OpenShiftProvisionerTestBase.getEap7LegacyS2iBuildTemplateApplication());
		provisioner.preDeploy();
		provisioner.deploy();
		openShift.configMaps().create(new ConfigMapBuilder().withNewMetadata().withName("no-delete").endMetadata().build());
		provisioner.undeploy();
		provisioner.postUndeploy();
		Assertions.assertNotNull(openShift.configMaps().withName("no-delete").get());
		openShift.configMaps().withName("no-delete").delete();
		// delete the images streams created by EapS2iBuildTemplateApplication
		openShift.imageStreams()
				.withName(((String) provisioner.getApplication().getParameters().get("EAP_IMAGE")).split(":")[0])
				.delete();
		openShift.imageStreams()
				.withName(((String) provisioner.getApplication().getParameters().get("EAP_RUNTIME_IMAGE")).split(":")[0])
				.delete();

		openShift.waiters().isProjectClean().waitFor();
	}
}
