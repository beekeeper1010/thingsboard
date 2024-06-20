/**
 * Copyright © 2016-2024 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.rule.engine.mqtt;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.common.util.ListeningExecutor;
import org.thingsboard.mqtt.MqttClient;
import org.thingsboard.mqtt.MqttClientConfig;
import org.thingsboard.mqtt.MqttConnectResult;
import org.thingsboard.rule.engine.AbstractRuleNodeUpgradeTest;
import org.thingsboard.rule.engine.TestDbCallbackExecutor;
import org.thingsboard.rule.engine.api.TbContext;
import org.thingsboard.rule.engine.api.TbNode;
import org.thingsboard.rule.engine.api.TbNodeConfiguration;
import org.thingsboard.rule.engine.api.TbNodeException;
import org.thingsboard.rule.engine.api.util.TbNodeUtils;
import org.thingsboard.rule.engine.credentials.AnonymousCredentials;
import org.thingsboard.rule.engine.credentials.BasicCredentials;
import org.thingsboard.rule.engine.credentials.CertPemCredentials;
import org.thingsboard.rule.engine.credentials.ClientCredentials;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.RuleNodeId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.msg.TbMsgType;
import org.thingsboard.server.common.data.msg.TbNodeConnectionType;
import org.thingsboard.server.common.data.rule.RuleNode;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgMetaData;

import javax.net.ssl.SSLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static com.amazonaws.util.StringUtils.UTF8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.spy;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;

@ExtendWith(MockitoExtension.class)
public class TbMqttNodeTest extends AbstractRuleNodeUpgradeTest {

    private final TenantId TENANT_ID =  TenantId.fromUUID(UUID.fromString("d0c5d2a8-3a6e-4c95-8caf-47fbdc8ef98f"));
    private final DeviceId DEVICE_ID = new DeviceId(UUID.fromString("09115d92-d333-432a-868c-ccd6e89c9287"));
    private final RuleNodeId RULE_NODE_ID = new RuleNodeId(UUID.fromString("11699e8f-c3f0-4366-9334-cbf75798314b"));
    private final ListeningExecutor executor = new TestDbCallbackExecutor();

    protected TbMqttNode mqttNode;
    protected TbMqttNodeConfiguration mqttNodeConfig;

    @Mock
    protected TbContext ctxMock;
    @Mock
    protected MqttClient mqttClientMock;
    @Mock
    protected EventLoopGroup eventLoopGroupMock;
    @Mock
    protected Promise<MqttConnectResult> promiseMock;
    @Mock
    protected MqttConnectResult resultMock;

    @BeforeEach
    protected void setUp() {
        mqttNode = spy(new TbMqttNode());
        mqttNodeConfig = new TbMqttNodeConfiguration().defaultConfiguration();
    }

    @Test
    public void verifyDefaultConfig() {
        assertThat(mqttNodeConfig.getTopicPattern()).isEqualTo("my-topic");
        assertThat(mqttNodeConfig.getHost()).isNull();
        assertThat(mqttNodeConfig.getPort()).isEqualTo(1883);
        assertThat(mqttNodeConfig.getConnectTimeoutSec()).isEqualTo(10);
        assertThat(mqttNodeConfig.getClientId()).isNull();
        assertThat(mqttNodeConfig.isAppendClientIdSuffix()).isFalse();
        assertThat(mqttNodeConfig.isRetainedMessage()).isFalse();
        assertThat(mqttNodeConfig.isCleanSession()).isTrue();
        assertThat(mqttNodeConfig.isSsl()).isFalse();
        assertThat(mqttNodeConfig.isParseToPlainText()).isFalse();
        assertThat(mqttNodeConfig.getCredentials()).isInstanceOf(AnonymousCredentials.class);
    }

    @Test
    public void verifyGetOwnerIdMethod() {
        String tenantIdStr = "6f67b6cc-21dd-46c5-809c-402b738a3f8b";
        String ruleNodeIdStr = "80a90b53-6888-4344-bf46-01ce8e96eee7";
        RuleNode ruleNode = new RuleNode(new RuleNodeId(UUID.fromString(ruleNodeIdStr)));
        given(ctxMock.getTenantId()).willReturn(TenantId.fromUUID(UUID.fromString(tenantIdStr)));
        given(ctxMock.getSelf()).willReturn(ruleNode);

        String actualOwnerIdStr = mqttNode.getOwnerId(ctxMock);
        String expectedOwnerIdStr = "Tenant[" + tenantIdStr + "]RuleNode[" + ruleNodeIdStr + "]";
        assertThat(actualOwnerIdStr).isEqualTo(expectedOwnerIdStr);
    }

    @Test
    public void verifyPrepareMqttClientConfigMethodWithBasicCredentials() throws SSLException {
        BasicCredentials credentials = new BasicCredentials();
        credentials.setUsername("test_username");
        credentials.setPassword("test_password");
        mqttNodeConfig.setCredentials(credentials);
        ReflectionTestUtils.setField(mqttNode, "mqttNodeConfiguration", mqttNodeConfig);
        MqttClientConfig mqttClientConfig = new MqttClientConfig(mqttNode.getSslContext());

        mqttNode.prepareMqttClientConfig(mqttClientConfig);

        assertThat(mqttClientConfig.getUsername()).isEqualTo("test_username");
        assertThat(mqttClientConfig.getPassword()).isEqualTo("test_password");
    }

    @ParameterizedTest
    @MethodSource
    public void verifyGetSslContextMethod(boolean ssl, ClientCredentials credentials, SslContext expectedSslContext) throws SSLException {
        mqttNodeConfig.setSsl(ssl);
        mqttNodeConfig.setCredentials(credentials);
        ReflectionTestUtils.setField(mqttNode, "mqttNodeConfiguration", mqttNodeConfig);

        SslContext actualSslContext = mqttNode.getSslContext();
        assertThat(actualSslContext)
                .usingRecursiveComparison()
                .ignoringFields("ctx", "ctxLock", "sessionContext.context.ctx", "sessionContext.context.ctxLock")
                .isEqualTo(expectedSslContext);
    }

    private static Stream<Arguments> verifyGetSslContextMethod() throws SSLException {
        return Stream.of(
                Arguments.of(true, new BasicCredentials(), SslContextBuilder.forClient().build()),
                Arguments.of(false, new AnonymousCredentials(), null)
        );
    }

    @Test
    public void givenSuccessfulConnectResult_whenInit_thenOk() throws ExecutionException, InterruptedException, TimeoutException {
        mqttNodeConfig.setClientId("bfrbTESTmfkr23");
        mqttNodeConfig.setAppendClientIdSuffix(true);
        mqttNodeConfig.setCredentials(new CertPemCredentials());

        mockConnectClient(mqttNode);
        given(promiseMock.get(anyLong(), any(TimeUnit.class))).willReturn(resultMock);
        given(resultMock.isSuccess()).willReturn(true);

        assertThatNoException().isThrownBy(() -> mqttNode.init(ctxMock, new TbNodeConfiguration(JacksonUtil.valueToTree(mqttNodeConfig))));
    }

    @Test
    public void givenFailedByTimeoutConnectResult_whenInit_thenThrowsException() throws ExecutionException, InterruptedException, TimeoutException {
        mqttNodeConfig.setHost("localhost");
        mqttNodeConfig.setClientId("bfrbTESTmfkr23");
        mqttNodeConfig.setCredentials(new CertPemCredentials());

        mockConnectClient(mqttNode);
        given(promiseMock.get(anyLong(), any(TimeUnit.class))).willThrow(new TimeoutException("Failed to connect"));

        assertThatThrownBy(() -> mqttNode.init(ctxMock, new TbNodeConfiguration(JacksonUtil.valueToTree(mqttNodeConfig))))
                .isInstanceOf(TbNodeException.class)
                .hasMessage("java.lang.RuntimeException: Failed to connect to MQTT broker at localhost:1883.")
                .extracting(e -> ((TbNodeException) e).isUnrecoverable())
                .isEqualTo(false);
    }

    @Test
    public void givenFailedConnectResult_whenInit_thenThrowsException() throws ExecutionException, InterruptedException, TimeoutException {
        mqttNodeConfig.setHost("localhost");
        mqttNodeConfig.setClientId("bfrbTESTmfkr23");
        mqttNodeConfig.setAppendClientIdSuffix(true);
        mqttNodeConfig.setCredentials(new CertPemCredentials());

        mockConnectClient(mqttNode);
        given(promiseMock.get(anyLong(), any(TimeUnit.class))).willReturn(resultMock);
        given(resultMock.isSuccess()).willReturn(false);
        given(resultMock.getReturnCode()).willReturn(MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED);

        assertThatThrownBy(() -> mqttNode.init(ctxMock, new TbNodeConfiguration(JacksonUtil.valueToTree(mqttNodeConfig))))
                .isInstanceOf(TbNodeException.class)
                .hasMessage("java.lang.RuntimeException: Failed to connect to MQTT broker at localhost:1883. Result code is: CONNECTION_REFUSED_NOT_AUTHORIZED")
                .extracting(e -> ((TbNodeException) e).isUnrecoverable())
                .isEqualTo(false);
    }

    @ParameterizedTest
    @MethodSource
    public void givenForceAckIsTrueAndTopicPatternAndIsRetainedMsgIsTrue_whenOnMsg_thenTellSuccess(String topicPattern, TbMsgMetaData metaData, String data) {
        mqttNodeConfig.setRetainedMessage(true);
        mqttNodeConfig.setTopicPattern(topicPattern);
        ReflectionTestUtils.setField(mqttNode, "mqttNodeConfiguration", mqttNodeConfig);
        ReflectionTestUtils.setField(mqttNode, "mqttClient", mqttClientMock);
        ReflectionTestUtils.setField(mqttNode, "forceAck", true);

        Future<Void> future = mock(Future.class);
        given(future.isSuccess()).willReturn(true);
        given(mqttClientMock.publish(any(String.class), any(ByteBuf.class), any(MqttQoS.class), anyBoolean())).willReturn(future);
        willAnswer(invocation-> {
            GenericFutureListener<Future<Void>> listener = invocation.getArgument(0);
            listener.operationComplete(future);
            return null;
        }).given(future).addListener(any());

        TbMsg msg = TbMsg.newMsg(TbMsgType.POST_TELEMETRY_REQUEST, DEVICE_ID, metaData, data);
        mqttNode.onMsg(ctxMock, msg);

        then(ctxMock).should().ack(msg);
        String expectedTopic = TbNodeUtils.processPattern(mqttNodeConfig.getTopicPattern(), msg);
        then(mqttClientMock).should().publish(expectedTopic, Unpooled.wrappedBuffer(msg.getData().getBytes(UTF8)), MqttQoS.AT_LEAST_ONCE, true);
        ArgumentCaptor<TbMsg> actualMsg = ArgumentCaptor.forClass(TbMsg.class);
        then(ctxMock).should().enqueueForTellNext(actualMsg.capture(), eq(TbNodeConnectionType.SUCCESS));
        assertThat(actualMsg.getValue()).usingRecursiveComparison().ignoringFields("ctx").isEqualTo(msg);
    }

    private static Stream<Arguments> givenForceAckIsTrueAndTopicPatternAndIsRetainedMsgIsTrue_whenOnMsg_thenTellSuccess() {
        return Stream.of(
                Arguments.of("new-topic", TbMsgMetaData.EMPTY, TbMsg.EMPTY_JSON_OBJECT),
                Arguments.of("${md-topic-name}", new TbMsgMetaData(Map.of("md-topic-name", "md-new-topic")), TbMsg.EMPTY_JSON_OBJECT),
                Arguments.of("$[msg-topic-name]", TbMsgMetaData.EMPTY, "{\"msg-topic-name\":\"msg-new-topic\"}")
        );
    }

    @Test
    public void givenForceAckIsFalseParseToPlainTextIsTrueAndMsgPublishingFailed_whenOnMsg_thenTellFailure() {
        mqttNodeConfig.setParseToPlainText(true);
        ReflectionTestUtils.setField(mqttNode, "mqttNodeConfiguration", mqttNodeConfig);
        ReflectionTestUtils.setField(mqttNode, "mqttClient", mqttClientMock);
        ReflectionTestUtils.setField(mqttNode, "forceAck", false);

        Future<Void> future = mock(Future.class);
        given(mqttClientMock.publish(any(String.class), any(ByteBuf.class), any(MqttQoS.class), anyBoolean())).willReturn(future);
        given(future.isSuccess()).willReturn(false);
        String errorMsg = "Message publishing was failed!";
        Throwable exception = new RuntimeException(errorMsg);
        given(future.cause()).willReturn(exception);
        willAnswer(invocation-> {
            GenericFutureListener<Future<Void>> listener = invocation.getArgument(0);
            listener.operationComplete(future);
            return null;
        }).given(future).addListener(any());

        TbMsg msg = TbMsg.newMsg(TbMsgType.POST_TELEMETRY_REQUEST, DEVICE_ID, TbMsgMetaData.EMPTY, "\"string\"");
        mqttNode.onMsg(ctxMock, msg);

        then(ctxMock).should(never()).ack(msg);
        String expectedData = JacksonUtil.toPlainText(msg.getData());
        then(mqttClientMock).should().publish(mqttNodeConfig.getTopicPattern(), Unpooled.wrappedBuffer(expectedData.getBytes(UTF8)), MqttQoS.AT_LEAST_ONCE, false);
        TbMsgMetaData metaData = new TbMsgMetaData();
        metaData.putValue("error", RuntimeException.class + ": " + errorMsg);
        TbMsg expectedMsg = TbMsg.transformMsgMetadata(msg, metaData);
        ArgumentCaptor<TbMsg> actualMsgCaptor = ArgumentCaptor.forClass(TbMsg.class);
        then(ctxMock).should().tellFailure(actualMsgCaptor.capture(), eq(exception));
        TbMsg actualMsg = actualMsgCaptor.getValue();
        assertThat(actualMsg).usingRecursiveComparison().ignoringFields("ctx").isEqualTo(expectedMsg);
    }

    @Test
    public void givenMqttClientIsNotNull_whenDestroy_thenDisconnect() {
        ReflectionTestUtils.setField(mqttNode, "mqttClient", mqttClientMock);
        mqttNode.destroy();
        then(mqttClientMock).should().disconnect();
    }

    @Test
    public void givenMqttClientIsNull_whenDestroy_thenShouldHaveNoInteractions() {
        ReflectionTestUtils.setField(mqttNode, "mqttClient", null);
        mqttNode.destroy();
        then(mqttClientMock).shouldHaveNoInteractions();
    }

    private static Stream<Arguments> givenFromVersionAndConfig_whenUpgrade_thenVerifyHasChangesAndConfig() {
        return Stream.of(
                // default config for version 0
                Arguments.of(0,
                        "{\"topicPattern\":\"my-topic\",\"port\":1883,\"connectTimeoutSec\":10,\"cleanSession\":true, \"ssl\":false, \"retainedMessage\":false,\"credentials\":{\"type\":\"anonymous\"}}",
                        true,
                        "{\"topicPattern\":\"my-topic\",\"port\":1883,\"connectTimeoutSec\":10,\"cleanSession\":true, \"ssl\":false, \"retainedMessage\":false,\"credentials\":{\"type\":\"anonymous\"},\"parseToPlainText\":false}"),
                // default config for version 1 with upgrade from version 0
                Arguments.of(1,
                        "{\"topicPattern\":\"my-topic\",\"port\":1883,\"connectTimeoutSec\":10,\"cleanSession\":true, \"ssl\":false, \"retainedMessage\":false,\"credentials\":{\"type\":\"anonymous\"},\"parseToPlainText\":false}",
                        false,
                        "{\"topicPattern\":\"my-topic\",\"port\":1883,\"connectTimeoutSec\":10,\"cleanSession\":true, \"ssl\":false, \"retainedMessage\":false,\"credentials\":{\"type\":\"anonymous\"},\"parseToPlainText\":false}")
        );

    }

    @Override
    protected TbNode getTestNode() {
        return mqttNode;
    }

    protected void mockConnectClient(TbMqttNode node) {
        given(ctxMock.getTenantId()).willReturn(TENANT_ID);
        given(ctxMock.getSelf()).willReturn(new RuleNode(RULE_NODE_ID));
        given(ctxMock.getExternalCallExecutor()).willReturn(executor);
        given(ctxMock.getSharedEventLoop()).willReturn(eventLoopGroupMock);
        willReturn(promiseMock).given(node).connectMqttClient(any());
    }

}
