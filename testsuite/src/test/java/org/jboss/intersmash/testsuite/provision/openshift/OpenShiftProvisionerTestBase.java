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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.assertj.core.util.Strings;
import org.jboss.intersmash.deployments.IntersmashSharedDeployments;
import org.jboss.intersmash.deployments.IntersmashSharedDeploymentsProperties;
import org.jboss.intersmash.deployments.WildflyDeploymentApplicationConfiguration;
import org.jboss.intersmash.testsuite.IntersmashTestsuiteProperties;
import org.jboss.intersmash.tools.IntersmashConfig;
import org.jboss.intersmash.tools.application.openshift.BootableJarOpenShiftApplication;
import org.jboss.intersmash.tools.application.openshift.Eap7ImageOpenShiftApplication;
import org.jboss.intersmash.tools.application.openshift.Eap7LegacyS2iBuildTemplateApplication;
import org.jboss.intersmash.tools.application.openshift.Eap7TemplateOpenShiftApplication;
import org.jboss.intersmash.tools.application.openshift.KafkaOperatorApplication;
import org.jboss.intersmash.tools.application.openshift.MysqlImageOpenShiftApplication;
import org.jboss.intersmash.tools.application.openshift.PostgreSQLImageOpenShiftApplication;
import org.jboss.intersmash.tools.application.openshift.PostgreSQLTemplateOpenShiftApplication;
import org.jboss.intersmash.tools.application.openshift.RhSsoTemplateOpenShiftApplication;
import org.jboss.intersmash.tools.application.openshift.WildflyImageOpenShiftApplication;
import org.jboss.intersmash.tools.application.openshift.input.BinarySource;
import org.jboss.intersmash.tools.application.openshift.input.BuildInput;
import org.jboss.intersmash.tools.application.openshift.input.BuildInputBuilder;
import org.jboss.intersmash.tools.application.openshift.template.Eap7Template;
import org.jboss.intersmash.tools.application.openshift.template.PostgreSQLTemplate;
import org.jboss.intersmash.tools.application.openshift.template.RhSsoTemplate;
import org.jboss.intersmash.tools.util.ProcessKeystoreGenerator;
import org.jboss.intersmash.tools.util.openshift.WildflyOpenShiftUtils;
import org.jboss.intersmash.tools.util.wildfly.Eap7CliScriptBuilder;

import cz.xtf.builder.builders.SecretBuilder;
import cz.xtf.builder.builders.secret.SecretType;
import cz.xtf.core.config.OpenShiftConfig;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.strimzi.api.kafka.model.AclOperation;
import io.strimzi.api.kafka.model.AclResourcePatternType;
import io.strimzi.api.kafka.model.AclRule;
import io.strimzi.api.kafka.model.AclRuleBuilder;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.KafkaTopic;
import io.strimzi.api.kafka.model.KafkaTopicBuilder;
import io.strimzi.api.kafka.model.KafkaUser;
import io.strimzi.api.kafka.model.KafkaUserBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListener;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OpenShiftProvisionerTestBase {
	static final EnvVar TEST_ENV_VAR = new EnvVarBuilder().withName("test-evn-key").withValue("test-evn-value").build();
	static final String TEST_SECRET_FOO = "foo";
	static final String TEST_SECRET_BAR = "bar";

	static final Secret TEST_SECRET = new SecretBuilder("test-secret")
			.setType(SecretType.OPAQUE).addData(TEST_SECRET_FOO, TEST_SECRET_BAR.getBytes()).build();

	static final String WILDFLY_TEST_PROPERTY = "test-property";

	static final String TEST_REPO = IntersmashConfig.deploymentsRepositoryUrl();
	static final String TEST_REF = IntersmashConfig.deploymentsRepositoryRef();

	static final String EAP7_TEST_APP_REPO = "https://github.com/openshift/openshift-jee-sample.git";
	static final String EAP7_TEST_APP_REF = "master";

	static RhSsoTemplateOpenShiftApplication getHttpsRhSso() {
		return new RhSsoTemplateOpenShiftApplication() {
			private final String secureAppHostname = "secure-" + getOpenShiftHostName();
			private final Path keystore = ProcessKeystoreGenerator.generateKeystore(secureAppHostname);
			private final String jceksFileName = "jgroups.jceks";
			private final Path truststore = ProcessKeystoreGenerator.getTruststore();

			@Override
			public String getName() {
				return "sso-app";
			}

			@Override
			public Map<String, String> getParameters() {
				Map<String, String> parameters = new HashMap<>();

				parameters.put("APPLICATION_NAME", getName());
				parameters.put("SSO_REALM", "eap-realm");
				parameters.put("SSO_SERVICE_USERNAME", "client");
				parameters.put("SSO_SERVICE_PASSWORD", "creator");
				parameters.put("SSO_ADMIN_USERNAME", "admin");
				parameters.put("SSO_ADMIN_PASSWORD", "admin");
				parameters.put("JGROUPS_CLUSTER_PASSWORD", "xpaasQEpassword");
				parameters.put("IMAGE_STREAM_NAMESPACE", OpenShiftConfig.namespace());

				return Collections.unmodifiableMap(parameters);
			}

			@Override
			public List<Secret> getSecrets() {
				SecretBuilder sb;
				try (InputStream is = getClass().getClassLoader().getResourceAsStream("certs/jgroups.jceks")) {
					sb = new SecretBuilder(getName() + "-secret")
							.addData(keystore.getFileName().toString(), keystore)
							.addData(jceksFileName, is)
							.addData(truststore.getFileName().toString(), truststore);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}

				return Collections.singletonList(sb.build());
			}

			@Override
			public RhSsoTemplate getTemplate() {
				return RhSsoTemplate.X509_HTTPS;
			}
		};
	}

	static Eap7TemplateOpenShiftApplication getEap7OpenShiftTemplateApplication() {
		return new Eap7TemplateOpenShiftApplication() {

			@Override
			public List<String> getCliScript() {
				Eap7CliScriptBuilder eapCliScriptBuilder = new Eap7CliScriptBuilder();
				eapCliScriptBuilder.addCommand(
						String.format("/system-property=%s:add(value=\"%s\")", WILDFLY_TEST_PROPERTY, WILDFLY_TEST_PROPERTY));
				return eapCliScriptBuilder.build();
			}

			@Override
			public Map<String, String> getParameters() {
				Map<String, String> map = new HashMap<>();
				map.put("SOURCE_REPOSITORY_URL", EAP7_TEST_APP_REPO);
				map.put("SOURCE_REPOSITORY_REF", EAP7_TEST_APP_REF);
				return Collections.unmodifiableMap(map);
			}

			@Override
			public List<Secret> getSecrets() {
				List<Secret> secrets = new ArrayList<>();
				secrets.add(TEST_SECRET);
				return Collections.unmodifiableList(secrets);
			}

			@Override
			public Eap7Template getTemplate() {
				return Eap7Template.BASIC;
			}

			@Override
			public String getName() {
				return "eap-test-app";
			}
		};
	}

	public static Eap7LegacyS2iBuildTemplateApplication getEap7LegacyS2iBuildTemplateApplication() {
		return new Eap7LegacyS2iBuildTemplateApplication() {
			private String eapImage;
			private String eapRuntimeImage;
			private final Map<String, String> parameters;
			private final List<EnvVar> envVars;

			{
				eapImage = WildflyOpenShiftUtils.importBuilderImage(IntersmashConfig.eap7ImageURL()).getMetadata().getName();
				eapRuntimeImage = WildflyOpenShiftUtils.importRuntimeImage(IntersmashConfig.eap7RuntimeImageUrl()).getMetadata()
						.getName();
				String deployment = "deployments/intersmash-deployments-shared/intersmash-deployments-shared-eap7/eap7-helloworld";
				//params
				parameters = new HashMap<>();
				parameters.put("APPLICATION_IMAGE", getName());
				parameters.put("EAP_IMAGE", eapImage);
				parameters.put("EAP_RUNTIME_IMAGE", eapRuntimeImage);
				parameters.put("EAP_IMAGESTREAM_NAMESPACE", OpenShiftConfig.namespace());
				parameters.put("SOURCE_REPOSITORY_URL", IntersmashConfig.deploymentsRepositoryUrl());
				parameters.put("SOURCE_REPOSITORY_REF", IntersmashConfig.deploymentsRepositoryRef());
				parameters.put("ARTIFACT_DIR", deployment + "/target");

				// envvars
				envVars = new ArrayList<>();
				// Set to allow cloning from Gitlab - any value here do the job
				envVars.add(new EnvVarBuilder().withName("GIT_SSL_NO_VERIFY").withValue("").build());
			}

			@Override
			public List<EnvVar> getEnvVars() {
				return envVars;
			}

			@Override
			public Map<String, String> getParameters() {
				return parameters;
			}

			@Override
			public String getName() {
				return "eap-s2i-build-application";
			}
		};
	}

	static BootableJarOpenShiftApplication getWildflyBootableJarOpenShiftApplication() {
		return new BootableJarOpenShiftApplication() {
			@Override
			public BinarySource getBuildInput() {
				return IntersmashSharedDeployments::bootableJarDemoOpenShift;
			}

			@Override
			public List<Secret> getSecrets() {
				List<Secret> secrets = new ArrayList<>();
				secrets.add(TEST_SECRET);
				return Collections.unmodifiableList(secrets);
			}

			@Override
			public List<EnvVar> getEnvVars() {
				List<EnvVar> list = new ArrayList<>();
				list.add(new EnvVarBuilder().withName(TEST_ENV_VAR.getName()).withValue(TEST_ENV_VAR.getValue()).build());
				return Collections.unmodifiableList(list);
			}

			@Override
			public String getName() {
				return "bootable-jar";
			}
		};
	}

	static BootableJarOpenShiftApplication getWildflyBootableJarJavaxOpenShiftApplication() {
		return new BootableJarOpenShiftApplication() {
			@Override
			public BinarySource getBuildInput() {
				return IntersmashSharedDeployments::bootableJarJavaxDemoOpenShift;
			}

			@Override
			public List<Secret> getSecrets() {
				List<Secret> secrets = new ArrayList<>();
				secrets.add(TEST_SECRET);
				return Collections.unmodifiableList(secrets);
			}

			@Override
			public List<EnvVar> getEnvVars() {
				List<EnvVar> list = new ArrayList<>();
				list.add(new EnvVarBuilder().withName(TEST_ENV_VAR.getName()).withValue(TEST_ENV_VAR.getValue()).build());
				return Collections.unmodifiableList(list);
			}

			@Override
			public String getName() {
				return "bootable-jar";
			}
		};
	}

	interface StaticWildflyImageOpenShiftDeploymentApplication
			extends WildflyImageOpenShiftApplication, WildflyDeploymentApplicationConfiguration {
	}

	static StaticWildflyImageOpenShiftDeploymentApplication getWildflyOpenShiftImageApplication() {
		return new StaticWildflyImageOpenShiftDeploymentApplication() {

			@Override
			public List<String> getCliScript() {
				return Arrays
						.asList(String.format("/system-property=%s:add(value=\"%s\")", WILDFLY_TEST_PROPERTY,
								WILDFLY_TEST_PROPERTY));
			}

			@Override
			public BuildInput getBuildInput() {
				return new BuildInputBuilder().uri(TEST_REPO).ref(TEST_REF).build();
			}

			@Override
			public List<EnvVar> getEnvVars() {
				List<EnvVar> list = new ArrayList<>();
				list.add(new EnvVarBuilder().withName(TEST_ENV_VAR.getName()).withValue(TEST_ENV_VAR.getValue()).build());
				// Let's skip addition of the CLI_LAUNCH_SCRIPT environment variable here to test that it's added automatically
				// in org.jboss.intersmash.tools.provision.openshift.WildflyImageOpenShiftProvisioner#deployImage().
				list.add(new EnvVarBuilder().withName("ADMIN_USERNAME").withValue("admin").build());
				list.add(new EnvVarBuilder().withName("ADMIN_PASSWORD").withValue("pass.1234").build());
				if (!Strings.isNullOrEmpty(this.getMavenMirrorUrl())) {
					list.add(new EnvVarBuilder().withName("MAVEN_MIRROR_URL")
							.withValue(this.getMavenMirrorUrl()).build());
				}

				final String deploymentRelativePath = "deployments/openshift-jakarta-sample-standalone/";
				// let's add configurable deployment additional args:
				// TODO - the `-Dmaven.wagon.http.ssl.insecure=true` property in the next line is a workaround
				//  and should be removed once Intersmash will run on OCP 4.8, which allows for importing custom CA
				String mavenAdditionalArgs = " -f " + deploymentRelativePath + "pom.xml -Dmaven.wagon.http.ssl.insecure=true";
				mavenAdditionalArgs = mavenAdditionalArgs.concat(generateAdditionalMavenArgs());
				// let's pass the profile for building the deployment too...
				mavenAdditionalArgs = mavenAdditionalArgs.concat(
						(Strings.isNullOrEmpty(IntersmashSharedDeploymentsProperties.getWildflyDeploymentsBuildProfile()) ? ""
								: " -Pwildfly-deployments-build."
										+ IntersmashSharedDeploymentsProperties.getWildflyDeploymentsBuildProfile()));
				list.add(new EnvVarBuilder().withName("MAVEN_ARGS_APPEND").withValue(mavenAdditionalArgs).build());
				list.add(new EnvVarBuilder().withName("ARTIFACT_DIR").withValue(deploymentRelativePath + "target").build());

				return Collections.unmodifiableList(list);
			}

			@Override
			public List<Secret> getSecrets() {
				List<Secret> secrets = new ArrayList<>();
				secrets.add(TEST_SECRET);
				return Collections.unmodifiableList(secrets);
			}

			@Override
			public String getName() {
				return "wildfly-test-app";
			}

			@Override
			public String getPingServiceName() {
				return "wildfly-ping-service";
			}
		};
	}

	public static StaticWildflyImageOpenShiftDeploymentApplication getWildflyOpenShiftLocalBinarySourceApplication() {
		return new StaticWildflyImageOpenShiftDeploymentApplication() {
			Path app = IntersmashSharedDeployments.findStandaloneDeploymentPath("openshift-jakarta-sample-standalone");

			@Override
			public List<String> getCliScript() {
				return Arrays
						.asList(String.format("/system-property=%s:add(value=\"%s\")", WILDFLY_TEST_PROPERTY,
								WILDFLY_TEST_PROPERTY));
			}

			@Override
			public BuildInput getBuildInput() {
				return new BuildInputBuilder().archive(app).build();
			}

			@Override
			public List<EnvVar> getEnvVars() {
				List<EnvVar> list = new ArrayList<>();
				list.add(new EnvVarBuilder().withName(TEST_ENV_VAR.getName()).withValue(TEST_ENV_VAR.getValue()).build());
				list.add(new EnvVarBuilder().withName("CLI_LAUNCH_SCRIPT").withValue("/opt/server/extensions/configure.cli")
						.build());

				list.add(new EnvVarBuilder().withName("ADMIN_USERNAME").withValue("admin").build());
				list.add(new EnvVarBuilder().withName("ADMIN_PASSWORD").withValue("pass.1234").build());
				if (!Strings.isNullOrEmpty(this.getMavenMirrorUrl())) {
					list.add(new EnvVarBuilder().withName("MAVEN_MIRROR_URL")
							.withValue(this.getMavenMirrorUrl()).build());
				}

				// let's add configurable deployment additional args:
				String mavenAdditionalArgs = generateAdditionalMavenArgs();
				// let's pass the profile for building the deployment too...
				mavenAdditionalArgs = mavenAdditionalArgs.concat(
						(Strings.isNullOrEmpty(IntersmashSharedDeploymentsProperties.getWildflyDeploymentsBuildProfile()) ? ""
								: " -Pwildfly-deployments-build."
										+ IntersmashSharedDeploymentsProperties.getWildflyDeploymentsBuildProfile()));
				if (!Strings.isNullOrEmpty(mavenAdditionalArgs)) {
					list.add(new EnvVarBuilder().withName("MAVEN_ARGS_APPEND").withValue(mavenAdditionalArgs).build());
				}

				return Collections.unmodifiableList(list);
			}

			@Override
			public List<Secret> getSecrets() {
				List<Secret> secrets = new ArrayList<>();
				secrets.add(TEST_SECRET);
				return Collections.unmodifiableList(secrets);
			}

			@Override
			public String getName() {
				return "wildfly-test-app";
			}

			@Override
			public String getPingServiceName() {
				return "wildfly-ping-service";

			}
		};
	}

	static WildflyImageOpenShiftApplication getWildflyOpenShiftLocalBinaryTargetServerApplication() {
		return new WildflyImageOpenShiftApplication() {
			Path app;

			private Path getApp() {
				if (app == null) {
					app = Paths.get(
							IntersmashSharedDeployments.findStandaloneDeploymentPath("openshift-jakarta-sample-standalone")
									.toFile().getAbsolutePath(),
							"target", "server");
				}
				return app;
			}

			@Override
			public List<String> getCliScript() {
				return Collections.emptyList();
			}

			@Override
			public BuildInput getBuildInput() {
				return new BuildInputBuilder().archive(getApp()).build();
			}

			@Override
			public List<EnvVar> getEnvVars() {
				List<EnvVar> list = new ArrayList<>();
				list.add(new EnvVarBuilder().withName("ADMIN_USERNAME").withValue("admin").build());
				list.add(new EnvVarBuilder().withName("ADMIN_PASSWORD").withValue("pass.1234").build());
				return Collections.unmodifiableList(list);
			}

			@Override
			public List<Secret> getSecrets() {
				List<Secret> secrets = new ArrayList<>();
				secrets.add(TEST_SECRET);
				return Collections.unmodifiableList(secrets);
			}

			@Override
			public String getName() {
				return "wildfly-test-app";
			}

			@Override
			public String getPingServiceName() {
				return "wildfly-ping-service";
			}
		};
	}

	public static MysqlImageOpenShiftApplication getMysqlOpenShiftApplication() {
		return new MysqlImageOpenShiftApplication() {

			@Override
			public String getUser() {
				return "user";
			}

			@Override
			public String getPassword() {
				return "password";
			}

			@Override
			public String getDbName() {
				return "mysqldb";
			}
		};
	}

	public static PostgreSQLImageOpenShiftApplication getPostgreSQLImageOpenShiftApplication() {
		return new PostgreSQLImageOpenShiftApplication() {

			@Override
			public String getUser() {
				return "user";
			}

			@Override
			public String getPassword() {
				return "password";
			}

			@Override
			public String getDbName() {
				return "psqldb";
			}
		};
	}

	public static PostgreSQLTemplateOpenShiftApplication getPostgreSQLTemplateOpenShiftApplication() {
		return new PostgreSQLTemplateOpenShiftApplication() {

			String NAME = "postgresql";

			@Override
			public PostgreSQLTemplate getTemplate() {
				return PostgreSQLTemplate.POSTGRESQL_EPHEMERAL;
			}

			@Override
			public String getName() {
				return NAME;
			}
		};
	}

	/**
	 * This method serves just for testing purpose. It implements necessary methods of {@link
	 * KafkaOperatorApplication} interface so we can successfully use it in our tests.
	 *
	 * @return instance of {@link KafkaOperatorApplication} to be used for test purposes
	 */
	public static KafkaOperatorApplication getKafkaApplication() {
		return new KafkaOperatorApplication() {
			static final String NAME = "kafka-test";
			private static final int KAFKA_INSTANCE_NUM = KafkaOperatorApplication.KAFKA_INSTANCE_NUM;
			private static final int TOPIC_RECONCILIATION_INTERVAL_SECONDS = KafkaOperatorApplication.TOPIC_RECONCILIATION_INTERVAL_SECONDS;
			private static final long USER_RECONCILIATION_INTERVAL_SECONDS = KafkaOperatorApplication.USER_RECONCILIATION_INTERVAL_SECONDS;

			private static final int KAFKA_PORT = 9092;

			private Kafka kafka;
			private List<KafkaTopic> topics;
			private List<KafkaUser> users;

			@Override
			public Kafka getKafka() {
				if (kafka == null) {
					final String kafkaVersion, kafkaProtocol;
					if (IntersmashTestsuiteProperties.isCommunityTestExecutionProfileEnabled()) {
						kafkaVersion = KafkaOperatorApplication.KAFKA_VERSION;
						kafkaProtocol = KafkaOperatorApplication.INTER_BROKER_PROTOCOL_VERSION;
					} else if (IntersmashTestsuiteProperties.isProductizedTestExecutionProfileEnabled()) {
						kafkaVersion = "3.6.0";
						kafkaProtocol = "3.6";
					} else {
						throw new IllegalStateException(
								String.format("Unknown Intersmash test suite execution profile: %s",
										IntersmashTestsuiteProperties.getTestExecutionProfile()));
					}

					Map<String, Object> config = new HashMap<>();
					config.put("inter.broker.protocol.version", kafkaProtocol);
					config.put("offsets.topic.replication.factor", KAFKA_INSTANCE_NUM);
					config.put("transaction.state.log.min.isr", KAFKA_INSTANCE_NUM);
					config.put("transaction.state.log.replication.factor", KAFKA_INSTANCE_NUM);

					config.put("default.replication.factor", KAFKA_INSTANCE_NUM);
					config.put("min.insync.replicas", 2);

					GenericKafkaListener listener = new GenericKafkaListener();
					listener.setName("plain");
					listener.setPort(KAFKA_PORT);
					listener.setType(KafkaListenerType.INTERNAL);
					listener.setTls(false);

					// Initialize Kafka resource
					kafka = new KafkaBuilder()
							.withNewMetadata().withName(NAME).endMetadata()
							.withNewSpec()
							.withNewEntityOperator()
							.withNewTopicOperator().withReconciliationIntervalSeconds(TOPIC_RECONCILIATION_INTERVAL_SECONDS)
							.endTopicOperator()
							.withNewUserOperator().withReconciliationIntervalSeconds(USER_RECONCILIATION_INTERVAL_SECONDS)
							.endUserOperator()
							.endEntityOperator()
							.withNewKafka()
							.withConfig(config)
							.withListeners(listener)
							.withNewKafkaAuthorizationSimple()
							.endKafkaAuthorizationSimple()
							.withReplicas(KAFKA_INSTANCE_NUM)
							.withNewEphemeralStorage().endEphemeralStorage()
							.withVersion(kafkaVersion)
							.endKafka()
							.withNewZookeeper()
							.withReplicas(KAFKA_INSTANCE_NUM)
							.withNewEphemeralStorage().endEphemeralStorage()
							.endZookeeper()
							.endSpec()
							.build();
				}

				return kafka;
			}

			@Override
			public List<KafkaTopic> getTopics() {
				if (topics == null) {
					topics = new LinkedList<>();

					Map<String, String> labels = new HashMap<>();
					labels.put("strimzi.io/cluster", NAME);

					KafkaTopic topic = new KafkaTopicBuilder()
							.withNewMetadata()
							.withName("test-topic")
							.withLabels(labels)
							.endMetadata()
							.withNewSpec()
							.withPartitions(1)
							.withReplicas(1)
							.endSpec()
							.build();

					topics.add(topic);
				}

				return topics;
			}

			@Override
			public List<KafkaUser> getUsers() {
				if (users == null) {
					users = new LinkedList<>();

					Map<String, String> labels = new HashMap<>();
					labels.put("strimzi.io/cluster", NAME);

					AclRule acl = new AclRuleBuilder()
							.withHost("*")
							.withOperation(AclOperation.DESCRIBE)
							.withNewAclRuleTopicResource()
							.withName("test-topic")
							.withPatternType(AclResourcePatternType.LITERAL)
							.endAclRuleTopicResource()
							.build();

					KafkaUser user = new KafkaUserBuilder()
							.withNewMetadata()
							.withName("test-user")
							.withLabels(labels)
							.endMetadata()
							.withNewSpec()
							.withNewKafkaUserTlsClientAuthentication()
							.endKafkaUserTlsClientAuthentication()
							.withNewKafkaUserAuthorizationSimple()
							.withAcls(acl)
							.endKafkaUserAuthorizationSimple()
							.endSpec()
							.build();

					users.add(user);
				}

				return users;
			}

			@Override
			public String getName() {
				return NAME;
			}
		};
	}

	static Eap7ImageOpenShiftApplication getEap7OpenShiftImageApplication() {
		return new Eap7ImageOpenShiftApplication() {

			@Override
			public List<String> getCliScript() {
				Eap7CliScriptBuilder cliScriptBuilder = new Eap7CliScriptBuilder();
				cliScriptBuilder.addCommand(
						String.format("/system-property=%s:add(value=\"%s\")", WILDFLY_TEST_PROPERTY, WILDFLY_TEST_PROPERTY));
				return cliScriptBuilder.build();
			}

			@Override
			public BuildInput getBuildInput() {
				return new BuildInputBuilder().uri(EAP7_TEST_APP_REPO).ref(EAP7_TEST_APP_REF).build();
			}

			@Override
			public List<EnvVar> getEnvVars() {
				List<EnvVar> list = new ArrayList<>();
				list.add(new EnvVarBuilder().withName(TEST_ENV_VAR.getName()).withValue(TEST_ENV_VAR.getValue()).build());
				return Collections.unmodifiableList(list);
			}

			@Override
			public List<Secret> getSecrets() {
				List<Secret> secrets = new ArrayList<>();
				secrets.add(TEST_SECRET);
				return Collections.unmodifiableList(secrets);
			}

			@Override
			public String getName() {
				return "wildfly-test-app";
			}

			@Override
			public String getPingServiceName() {
				return "wildfly-ping-service";
			}
		};
	}
}
