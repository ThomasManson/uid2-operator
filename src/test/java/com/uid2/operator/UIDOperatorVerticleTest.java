// Copyright (c) 2021 The Trade Desk, Inc
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice,
//    this list of conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

package com.uid2.operator;

import com.uid2.operator.model.AdvertisingToken;
import com.uid2.shared.model.EncryptionKey;
import com.uid2.operator.model.RefreshResponse;
import com.uid2.operator.model.RefreshToken;
import com.uid2.operator.service.TokenUtils;
import com.uid2.operator.service.V2EncryptedTokenEncoder;
import com.uid2.operator.store.*;
import com.uid2.operator.vertx.UIDOperatorVerticle;
import com.uid2.shared.auth.ClientKey;
import com.uid2.shared.auth.Role;
import com.uid2.shared.store.IClientKeyProvider;
import com.uid2.shared.store.IKeyAclProvider;
import com.uid2.shared.store.IKeyStore;
import com.uid2.shared.store.ISaltProvider;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(VertxExtension.class)
public class UIDOperatorVerticleTest {
    private AutoCloseable mocks;
    @Mock private IClientKeyProvider clientKeyProvider;
    @Mock private IKeyStore keyStore;
    @Mock private IKeyStore.IKeyStoreSnapshot keyStoreSnapshot;
    @Mock private IKeyAclProvider keyAclProvider;
    @Mock private IKeyAclProvider.IKeysAclSnapshot keyAclProviderSnapshot;
    @Mock private ISaltProvider saltProvider;
    @Mock private ISaltProvider.ISaltSnapshot saltProviderSnapshot;
    @Mock private IOptOutStore optOutStore;
    private static final String firstLevelSalt = "first-level-salt";
    private static final ISaltProvider.SaltEntry rotatingSalt123 = new ISaltProvider.SaltEntry(123, "hashed123", 0, "salt123");

    @BeforeEach void deployVerticle(Vertx vertx, VertxTestContext testContext) throws Throwable {
        mocks = MockitoAnnotations.openMocks(this);
        UIDOperatorVerticle verticle = new UIDOperatorVerticle(clientKeyProvider, keyStore, keyAclProvider, saltProvider, optOutStore);
        vertx.deployVerticle(verticle, testContext.succeeding(id -> testContext.completeNow()));
        when(keyStore.getSnapshot()).thenReturn(keyStoreSnapshot);
        when(keyAclProvider.getSnapshot()).thenReturn(keyAclProviderSnapshot);
        when(saltProvider.getSnapshot()).thenReturn(saltProviderSnapshot);
    }

    @AfterEach void teardown() throws Exception {
        mocks.close();
    }

    private static byte[] makeAesKey(String prefix) {
        return String.format("%1$16s", prefix).getBytes();
    }

    private void addEncryptionKeys(EncryptionKey... keys) {
        when(keyStoreSnapshot.getActiveKeySet()).thenReturn(Arrays.asList(keys));
    }

    private void fakeAuth(int siteId, Role... roles) {
        ClientKey clientKey = new ClientKey("test-key").withSiteId(siteId).withRoles(roles);
        when(clientKeyProvider.get(any())).thenReturn(clientKey);
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return null;
        }
    }

    private String getUrlForEndpoint(String endpoint) {
        return String.format("http://127.0.0.1:%d/%s", Const.Port.ServicePortForOperator, endpoint);
    }

    private void get(Vertx vertx, String endpoint, Handler<AsyncResult<HttpResponse<Buffer>>> handler) {
        WebClient client = WebClient.create(vertx);
        client.getAbs(getUrlForEndpoint(endpoint)).send(handler);
    }

    private void checkEncryptionKeysResponse(JsonObject response, EncryptionKey... expectedKeys) {
        assertEquals("success", response.getString("status"));
        final JsonArray responseKeys = response.getJsonArray("body");
        assertNotNull(responseKeys);
        assertEquals(expectedKeys.length, responseKeys.size());
        for(int i = 0; i < expectedKeys.length; ++i) {
            EncryptionKey expectedKey = expectedKeys[i];
            JsonObject actualKey = responseKeys.getJsonObject(i);
            assertEquals(expectedKey.getId(), actualKey.getInteger("id"));
            assertArrayEquals(expectedKey.getKeyBytes(), actualKey.getBinary("secret"));
            assertEquals(expectedKey.getCreated().truncatedTo(ChronoUnit.SECONDS), Instant.ofEpochSecond(actualKey.getLong("created")));
            assertEquals(expectedKey.getActivates().truncatedTo(ChronoUnit.SECONDS), Instant.ofEpochSecond(actualKey.getLong("activates")));
            assertEquals(expectedKey.getExpires().truncatedTo(ChronoUnit.SECONDS), Instant.ofEpochSecond(actualKey.getLong("expires")));
            assertEquals(expectedKey.getSiteId(), actualKey.getInteger("site_id"));
        }
    }

    private void setupSalts() {
        when(saltProviderSnapshot.getFirstLevelSalt()).thenReturn(firstLevelSalt);
        when(saltProviderSnapshot.getRotatingSalt(any())).thenReturn(rotatingSalt123);
    }

    private void setupKeys() {
        EncryptionKey masterKey = new EncryptionKey(101, makeAesKey("masterKey"), Instant.now().minusSeconds(7), Instant.now(), Instant.now().plusSeconds(10), -1);
        EncryptionKey siteKey = new EncryptionKey(102, makeAesKey("siteKey"), Instant.now().minusSeconds(7), Instant.now(), Instant.now().plusSeconds(10), Const.Data.AdvertisingTokenSiteId);
        when(keyAclProviderSnapshot.canClientAccessKey(any(), any())).thenReturn(true);
        when(keyStoreSnapshot.getMasterKey()).thenReturn(masterKey);
        when(keyStoreSnapshot.getActiveSiteKey(eq(Const.Data.AdvertisingTokenSiteId), any())).thenReturn(siteKey);
        when(keyStoreSnapshot.getKey(101)).thenReturn(masterKey);
        when(keyStoreSnapshot.getKey(102)).thenReturn(siteKey);
    }

    @Test void verticleDeployed(Vertx vertx, VertxTestContext testContext) {
        testContext.completeNow();
    }

    @Test void keyLatestNoAcl(Vertx vertx, VertxTestContext testContext) {
        fakeAuth(205, Role.ID_READER);
        EncryptionKey[] encryptionKeys = {
                new EncryptionKey(101, "key101".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 201),
                new EncryptionKey(102, "key102".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 202),
        };
        addEncryptionKeys(encryptionKeys);
        when(keyAclProviderSnapshot.canClientAccessKey(any(), any())).thenReturn(true);
        get(vertx, "v1/key/latest", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            checkEncryptionKeysResponse(response.bodyAsJsonObject(), encryptionKeys);
            testContext.completeNow();
        });
    }

    @Test void keyLatestWithAcl(Vertx vertx, VertxTestContext testContext) {
        fakeAuth(205, Role.ID_READER);
        EncryptionKey[] encryptionKeys = {
                new EncryptionKey(101, "key101".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 201),
                new EncryptionKey(102, "key102".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 202),
        };
        addEncryptionKeys(encryptionKeys);
        when(keyAclProviderSnapshot.canClientAccessKey(any(), any())).then((i) -> {
            return i.getArgument(1, EncryptionKey.class).getId() > 101;
        });
        get(vertx, "v1/key/latest", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            checkEncryptionKeysResponse(response.bodyAsJsonObject(), Arrays.copyOfRange(encryptionKeys, 1, 2));
            testContext.completeNow();
        });
    }

    @Test void keyLatestClientBelongsToReservedSiteId(Vertx vertx, VertxTestContext testContext) {
        fakeAuth(Const.Data.AdvertisingTokenSiteId, Role.ID_READER);
        EncryptionKey[] encryptionKeys = {
                new EncryptionKey(101, "key101".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 201),
                new EncryptionKey(102, "key102".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 202),
        };
        addEncryptionKeys(encryptionKeys);
        when(keyAclProviderSnapshot.canClientAccessKey(any(), any())).thenReturn(true);
        get(vertx, "v1/key/latest", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(401, response.statusCode());
            testContext.completeNow();
        });
    }

    @Test void tokenGenerateForEmail(Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailAddress = "test@uid2.com";
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();
        get(vertx, "token/generate?email=" + emailAddress, ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            JsonObject json = response.bodyAsJsonObject();
            V2EncryptedTokenEncoder encoder = new V2EncryptedTokenEncoder(keyStore);

            AdvertisingToken advertisingToken = encoder.decodeAdvertisingToken(json.getBinary("advertising_token"));
            assertEquals(clientSiteId, advertisingToken.getIdentity().getSiteId());
            assertEquals(TokenUtils.getAdvertisingIdFromEmail(emailAddress, firstLevelSalt, rotatingSalt123.getSalt()), advertisingToken.getIdentity().getId());

            RefreshToken refreshToken = encoder.decode(json.getBinary("refresh_token"));
            assertEquals(clientSiteId, refreshToken.getIdentity().getSiteId());
            assertEquals(TokenUtils.getFirstLevelKey(TokenUtils.getEmailHash(emailAddress), firstLevelSalt), refreshToken.getIdentity().getId());

            testContext.completeNow();
        });
    }

    @Test void tokenGenerateForEmailHash(Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailHash = TokenUtils.getEmailHash("test@uid2.com");
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();
        get(vertx, "token/generate?email_hash=" + urlEncode(emailHash), ar -> {
            assertTrue(ar.succeeded());
            HttpResponse response = ar.result();
            assertEquals(200, response.statusCode());
            JsonObject json = response.bodyAsJsonObject();
            V2EncryptedTokenEncoder encoder = new V2EncryptedTokenEncoder(keyStore);

            AdvertisingToken advertisingToken = encoder.decodeAdvertisingToken(json.getBinary("advertising_token"));
            assertEquals(clientSiteId, advertisingToken.getIdentity().getSiteId());
            assertEquals(TokenUtils.getAdvertisingIdFromEmailHash(emailHash, firstLevelSalt, rotatingSalt123.getSalt()), advertisingToken.getIdentity().getId());

            RefreshToken refreshToken = encoder.decode(json.getBinary("refresh_token"));
            assertEquals(clientSiteId, refreshToken.getIdentity().getSiteId());
            assertEquals(TokenUtils.getFirstLevelKey(emailHash, firstLevelSalt), refreshToken.getIdentity().getId());

            testContext.completeNow();
        });
    }

    @Test void tokenGenerateThenRefresh(Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailAddress = "test@uid2.com";
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();
        get(vertx, "token/generate?email=" + emailAddress, arGen -> {
            assertTrue(arGen.succeeded());
            HttpResponse responseGen = arGen.result();
            assertEquals(200, responseGen.statusCode());
            JsonObject jsonGen = responseGen.bodyAsJsonObject();
            String refreshTokenString = jsonGen.getString("refresh_token");

            doAnswer(i -> {
                Handler<AsyncResult<RefreshResponse>> handler = i.getArgument(1);
                handler.handle(Future.succeededFuture());
                return null;
            }).when(this.optOutStore).getLatestEntry(any(), any());

            get(vertx, "token/refresh?refresh_token=" + urlEncode(refreshTokenString), ar -> {
                assertTrue(ar.succeeded());
                HttpResponse response = ar.result();
                assertEquals(200, response.statusCode());
                JsonObject json = response.bodyAsJsonObject();
                V2EncryptedTokenEncoder encoder = new V2EncryptedTokenEncoder(keyStore);

                AdvertisingToken advertisingToken = encoder.decodeAdvertisingToken(json.getBinary("advertising_token"));
                assertEquals(clientSiteId, advertisingToken.getIdentity().getSiteId());
                assertEquals(TokenUtils.getAdvertisingIdFromEmail(emailAddress, firstLevelSalt, rotatingSalt123.getSalt()), advertisingToken.getIdentity().getId());

                assertNotEquals(refreshTokenString, json.getString("refresh_token"));
                RefreshToken refreshToken = encoder.decode(json.getBinary("refresh_token"));
                assertEquals(clientSiteId, refreshToken.getIdentity().getSiteId());
                assertEquals(TokenUtils.getFirstLevelKey(TokenUtils.getEmailHash(emailAddress), firstLevelSalt), refreshToken.getIdentity().getId());

                testContext.completeNow();
            });
        });
    }
}