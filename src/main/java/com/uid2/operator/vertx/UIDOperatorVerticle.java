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

package com.uid2.operator.vertx;

import com.uid2.operator.Const;
import com.uid2.operator.model.*;
import com.uid2.operator.service.*;
import com.uid2.operator.store.*;
import com.uid2.shared.Utils;
import com.uid2.shared.auth.ClientKey;
import com.uid2.shared.auth.Role;
import com.uid2.shared.health.HealthComponent;
import com.uid2.shared.health.HealthManager;
import com.uid2.shared.middleware.AuthMiddleware;
import com.uid2.shared.model.EncryptionKey;
import com.uid2.shared.store.IClientKeyProvider;
import com.uid2.shared.store.IKeyAclProvider;
import com.uid2.shared.store.IKeyStore;
import com.uid2.shared.store.ISaltProvider;
import com.uid2.shared.vertx.RequestCapturingHandler;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class UIDOperatorVerticle extends AbstractVerticle {
    private static final Logger LOGGER = LoggerFactory.getLogger(UIDOperatorVerticle.class);

    private static String ValidationInput = EncodingUtils.getSha256("validate@email.com");
    private static DateTimeFormatter APIDateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.of("UTC"));
    private final HealthComponent healthComponent = HealthManager.instance.registerComponent("http-server");
    private final AuthMiddleware auth;
    private final IKeyStore keyStore;
    private final IKeyAclProvider keyAclProvider;
    private final ISaltProvider saltProvider;
    private final IOptOutStore optOutStore;
    private IUIDOperatorService idService;

    public UIDOperatorVerticle(IClientKeyProvider clientKeyProvider,
                               IKeyStore keyStore,
                               IKeyAclProvider keyAclProvider,
                               ISaltProvider saltProvider,
                               IOptOutStore optOutStore) {
        this.healthComponent.setHealthStatus(false, "not started");
        this.auth = new AuthMiddleware(clientKeyProvider);
        this.keyStore = keyStore;
        this.keyAclProvider = keyAclProvider;
        this.saltProvider = saltProvider;
        this.optOutStore = optOutStore;
    }

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        this.healthComponent.setHealthStatus(false, "still starting");

        this.idService = new UIDOperatorService(
            this.keyStore,
            this.optOutStore,
            this.saltProvider,
            new V2EncryptedTokenEncoder(this.keyStore)
        );

        final Router router = createRoutesSetup();
        final int port = Const.Port.ServicePortForOperator +  Utils.getPortOffset();
        LOGGER.info("starting service on http.port: " + port);
        vertx
            .createHttpServer()
            .requestHandler(router::handle)
            .listen(port, result -> {
                if (result.succeeded()) {
                    this.healthComponent.setHealthStatus(true);
                    startPromise.complete();
                } else {
                    this.healthComponent.setHealthStatus(false, result.cause().getMessage());
                    startPromise.fail(result.cause());
                }

                LOGGER.info("UIDOperatorVerticle instance started");
            });

    }

    private Router createRoutesSetup() {
        final Router router = Router.router(vertx);

        router.route().handler(BodyHandler.create());
        router.route().handler(new RequestCapturingHandler());
        router.route().handler(CorsHandler.create(".*.")
            .allowedMethod(io.vertx.core.http.HttpMethod.GET)
            .allowedMethod(io.vertx.core.http.HttpMethod.POST)
            .allowedMethod(io.vertx.core.http.HttpMethod.OPTIONS)
            .allowedHeader("Access-Control-Request-Method")
            .allowedHeader("Access-Control-Allow-Credentials")
            .allowedHeader("Access-Control-Allow-Origin")
            .allowedHeader("Access-Control-Allow-Headers")
            .allowedHeader("Content-Type"));

        // Current version APIs
        router.get("/v1/token/generate").handler(auth.handleV1(this::handleTokenGenerateV1, Role.GENERATOR));
        router.get("/v1/token/validate").handler(this::handleTokenValidateV1);
        router.get("/v1/token/refresh-back").handler(auth.handle(this::handleTokenRefreshV1));
        router.get("/v1/token/refresh").handler(auth.handle(this::handleTokenRefreshV1Async));
        router.get("/v1/identity/buckets").handler(auth.handle(this::handleBucketsV1, Role.MAPPER));
        router.get("/v1/identity/map").handler(auth.handle(this::handleIdentityMapV1, Role.MAPPER));
        router.post("/v1/identity/map").handler(auth.handle(this::handleIdentityMapBatchV1, Role.MAPPER));
        router.get("/v1/key/latest").handler(auth.handle(this::handleKeysRequestV1, Role.ID_READER));

        // Deprecated APIs
        router.get("/key/latest").handler(auth.handle(this::handleKeysRequest, Role.ID_READER));
        router.get("/token/generate").handler(auth.handle(this::handleTokenGenerate, Role.GENERATOR));
        router.get("/token/refresh").handler(this::handleTokenRefresh);
        router.get("/token/validate").handler(this::handleValidate);
        router.get("/identity/map").handler(auth.handle(this::handleIdentityMap, Role.MAPPER));
        router.post("/identity/map").handler(auth.handle(this::handleIdentityMapBatch, Role.MAPPER));

        // Internal service APIs and static data
        router.get("/token/logout").handler(auth.handle(this::handleLogoutAsync, Role.OPTOUT));
        router.get("/ops/healthcheck").handler(this::handleHealthCheck);
        router.route("/static/*").handler(StaticHandler.create("static"));

        // only uncomment to do local testing
        //router.get("/internal/optout/get").handler(auth.loopbackOnly(this::handleOptOutGet));

        return router;
    }

    private void handleKeysRequestCommon(RoutingContext rc, Handler<JsonArray> onSuccess) {
        final ClientKey clientKey = (ClientKey)AuthMiddleware.getAuthClient(rc);
        final int clientSiteId = clientKey.getSiteId();
        if(!clientKey.hasValidSiteId()) {
            ResponseUtil.Error("invalid_client", 401, rc, "Unexpected client site id " + Integer.toString(clientSiteId));
            return;
        }

        final List<EncryptionKey> keys = this.keyStore.getSnapshot().getActiveKeySet();
        final IKeyAclProvider.IKeysAclSnapshot acls = this.keyAclProvider.getSnapshot();
        onSuccess.handle(toJson(keys, clientKey, acls));
    }

    public void handleKeysRequestV1(RoutingContext rc) {
        try {
            handleKeysRequestCommon(rc, keys -> ResponseUtil.Success(rc, keys));
        } catch (Exception e) {
            e.printStackTrace();
            rc.fail(500);
        }
    }

    public void handleKeysRequest(RoutingContext rc) {
        try {
            handleKeysRequestCommon(rc, keys -> sendJsonResponse(rc, keys));
        } catch (Exception e) {
            e.printStackTrace();
            rc.fail(500);
        }
    }

    private void handleHealthCheck(RoutingContext rc) {
        if (HealthManager.instance.isHealthy()) {
            rc.response().end("OK");
        } else {
            HttpServerResponse resp = rc.response();
            String reason = HealthManager.instance.reason();
            resp.setStatusCode(503);
            resp.setChunked(true);
            resp.write(reason);
            resp.end();
        }
    }

    private void handleTokenRefreshV1Async(RoutingContext rc, boolean isAuthorized) {
        final List<String> tokenList = rc.queryParam("refresh_token");
        if (tokenList == null || tokenList.size() == 0) {
            ResponseUtil.ClientError(rc, "Required Parameter Missing: refresh_token");
            return;
        }

        try {

            this.idService.refreshIdentityAsync(tokenList.get(0), ar -> {
                if (ar.succeeded()) {
                    final RefreshResponse r = ar.result();
                    if (isAuthorized && !r.isRefreshed()) {
                        if (r.isInvalidToken()) {
                            ResponseUtil.Error("invalid_token", 400, rc, "Invalid Token presented " + tokenList.get(0));
                        } else if (r.isOptOut() || r.isDeprecated()) {
                            ResponseUtil.SuccessNoBody("optout", rc);
                        } else {
                            ResponseUtil.Error("unknown", 500, rc, "Unknown State");
                        }
                    } else {
                        ResponseUtil.Success(rc, toJsonV1(r.getTokens()));
                    }
                } else {
                    ResponseUtil.Error("unknown", 500, rc, "Service Error");
                }

            });
        } catch (Exception e) {
            e.printStackTrace();
            rc.fail(500);
        }

    }

    private void handleTokenRefreshV1(RoutingContext rc, boolean isAuthorized) {

        final List<String> tokenList = rc.queryParam("refresh_token");
        if (tokenList == null || tokenList.size() == 0) {
            ResponseUtil.ClientError(rc, "Required Parameter Missing: refresh_token");
            return;
        }

        try {
            final RefreshResponse r = this.idService.refreshIdentity(tokenList.get(0));

            if (isAuthorized && !r.isRefreshed()) {
                if (r.isInvalidToken()) {
                    ResponseUtil.Error("invalid_token", 400, rc, "Invalid Token presented " + tokenList.get(0));
                } else if (r.isOptOut()) {
                    ResponseUtil.SuccessNoBody("optout", rc);
                } else {
                    ResponseUtil.Error("unknown", 500, rc, "Unknown State");
                }
            } else {
                ResponseUtil.Success(rc, toJsonV1(r.getTokens()));
            }
        } catch (Exception e) {
            e.printStackTrace();
            rc.fail(500);
        }
    }

    private void handleTokenValidateV1(RoutingContext rc) {
        try {
            final InputUtil.InputVal input = getTokenInput(rc);
            if (input == null || !input.isValid()) {
                ResponseUtil.ClientError(rc, "Required Parameter Missing: email or email_hash");
                return;
            }
            if (ValidationInput.equals(input.getIdentityInput())) {
                try {
                    if (this.idService.doesMatch(rc.queryParam("token").get(0), input.getIdentityInput())) {
                        ResponseUtil.Success(rc, Boolean.TRUE);
                    } else {
                        ResponseUtil.Success(rc, Boolean.FALSE);
                    }
                } catch (Exception e) {
                    ResponseUtil.Success(rc, Boolean.FALSE);
                }
            } else {
                ResponseUtil.Success(rc, Boolean.FALSE);
            }
        } catch (Exception e) {
            e.printStackTrace();
            rc.fail(500);
        }

    }

    private void handleTokenGenerateV1(RoutingContext rc) {
        try {
            final InputUtil.InputVal input = this.getTokenInput(rc);
            if (input == null || !input.isValid()) {
                ResponseUtil.ClientError(rc, "Required Parameter Missing: email or email_hash");
            } else {
                final ClientKey clientKey = (ClientKey)AuthMiddleware.getAuthClient(rc);
                final IdentityTokens t = this.idService.generateIdentity(
                    new IdentityRequest(input.getIdentityInput(), clientKey.getSiteId(), 1));

                //Integer.parseInt(rc.queryParam("privacy_bits").get(0))));

                ResponseUtil.Success(rc, toJsonV1(t));
            }
        } catch (Exception e) {
            e.printStackTrace();
            rc.fail(500);
        }
    }

    private void handleTokenGenerate(RoutingContext rc) {
        final InputUtil.InputVal input = this.getTokenInput(rc);
        if (input == null || !input.isValid()) {
            rc.fail(400);
            return;
        }

        try {
            final ClientKey clientKey = (ClientKey)AuthMiddleware.getAuthClient(rc);
            final IdentityTokens t = this.idService.generateIdentity(
                new IdentityRequest(input.getIdentityInput(), clientKey.getSiteId(), 1));

            //Integer.parseInt(rc.queryParam("privacy_bits").get(0))));

            sendJsonResponse(rc, toJson(t));

        } catch (Exception e) {
            e.printStackTrace();
            rc.fail(500);
        }
    }

    private void handleTokenRefresh(RoutingContext rc) {
        final List<String> tokenList = rc.queryParam("refresh_token");
        if (tokenList == null || tokenList.size() == 0) {
            rc.fail(500);
            return;
        }

        try {

            this.idService.refreshIdentityAsync(tokenList.get(0), ar -> {
                if (ar.succeeded()) {
                    final RefreshResponse r = ar.result();
                    sendJsonResponse(rc, toJson(r.getTokens()));
                } else {
                    rc.fail(500);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            rc.fail(500);
        }
    }

    private void handleValidate(RoutingContext rc) {
        try {
            final InputUtil.InputVal input = getTokenInput(rc);
            if (input != null && input.isValid() && ValidationInput.equals(input.getIdentityInput())) {
                try {
                    if (this.idService.doesMatch(rc.queryParam("token").get(0), input.getIdentityInput())) {
                        rc.response().end("true");
                    } else {
                        rc.response().end("false");
                    }
                } catch (Exception e) {
                    rc.response().end("false");
                }
            } else {
                rc.response().end("not allowed");
            }
        } catch (Exception e) {
            e.printStackTrace();
            rc.fail(500);
        }
    }

    private void handleLogoutAsync(RoutingContext rc) {
        final InputUtil.InputVal input = getTokenInput(rc);
        if (input.isValid()) {
            this.idService.InvalidateTokensAsync(input.getIdentityInput(), ar -> {
                if (ar.succeeded()) {
                    rc.response().end("OK");
                } else {
                    rc.fail(500);
                }
            });
        } else {
            rc.fail(400);
        }
    }

    private void handleOptOutGet(RoutingContext rc) {
        final InputUtil.InputVal input = getTokenInput(rc);
        if (input.isValid()) {
            this.optOutStore.getLatestEntry(input.getIdentityInput(), ar -> {
                if (ar.failed()) {
                    rc.fail(500);
                }
                else {
                    Instant result = ar.result();
                    long timestamp = result == null ? -1 : result.getEpochSecond();
                    rc.response().setStatusCode(200)
                        .setChunked(true)
                        .write(String.valueOf(timestamp))
                        .end();
                }
            });
        } else {
            rc.fail(400);
        }
    }

    private void handleBucketsV1(RoutingContext rc) {
        final List<String> qp = rc.queryParam("since_timestamp");
        if (qp != null && qp.size() > 0) {
            final Instant sinceTimestamp;
            try {
                LocalDateTime ld = LocalDateTime.parse(qp.get(0), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                sinceTimestamp = ld.toInstant(ZoneOffset.UTC);
            } catch (Exception e) {
                ResponseUtil.ClientError(rc, "invalid date, must conform to ISO 8601");
                return;
            }
            final List<ISaltProvider.SaltEntry> modified = this.idService.getModifiedBuckets(sinceTimestamp);
            final JsonArray resp = new JsonArray();
            if (modified != null) {
                for (ISaltProvider.SaltEntry e : modified) {
                    final JsonObject o = new JsonObject();
                    o.put("bucket_id", e.getHashedId());
                    Instant lastUpdated = Instant.ofEpochMilli(e.getLastUpdated());

                    o.put("last_updated", APIDateTimeFormatter.format(lastUpdated));
                    resp.add(o);
                }
                ResponseUtil.Success(rc, resp);
            }
        } else {
            ResponseUtil.ClientError(rc, "missing parameter since_timestamp");
        }
    }

    private void handleIdentityMapV1(RoutingContext rc) {
        final InputUtil.InputVal input = this.getTokenInput(rc);
        if (input == null || !input.isValid()) {
            ResponseUtil.ClientError(rc, "invalid identifier");
            return;
        }
        try {

            final MappedIdentity mappedIdentity = this.idService.map(input.getIdentityInput());
            final JsonObject jsonObject = new JsonObject();
            jsonObject.put("identifier", input.getProvided());
            jsonObject.put("advertising_id", mappedIdentity.getAdvertisingId());
            jsonObject.put("bucket_id", mappedIdentity.getBucketId());
            ResponseUtil.Success(rc, jsonObject);
        } catch (Exception e) {
            ResponseUtil.Error("unknown", 500, rc, "Unknown State");
        }
    }

    private void handleIdentityMap(RoutingContext rc) {
        final InputUtil.InputVal input = this.getTokenInput(rc);

        if (input != null && input.isValid()) {
            final MappedIdentity mappedIdentity = this.idService.map(input.getIdentityInput());
            rc.response().end(mappedIdentity.getAdvertisingId());
        } else {
            rc.fail(400);
        }

    }

    private InputUtil.InputVal getTokenInput(RoutingContext rc) {
        final InputUtil.InputVal input;
        final List<String> emailInput = rc.queryParam("email");
        if (emailInput != null && emailInput.size() > 0) {
            input = InputUtil.NormalizeEmail(emailInput.get(0));
        } else {
            final List<String> emailHashInput = rc.queryParam("email_hash");
            if (emailHashInput != null && emailHashInput.size() > 0) {
                input = InputUtil.NormalizeHash(emailHashInput.get(0));
            } else {
                input = null;
            }
        }
        return input;

    }

    private void handleIdentityMapBatchV1(RoutingContext rc) {
        try {
            final JsonObject obj = rc.getBodyAsJson();
            final InputUtil.InputVal[] inputList;
            final JsonArray emails = obj.getJsonArray("email");
            final JsonArray inputArray;
            // FIXME TODO. Avoid Double Iteration. Turn to a decorator pattern
            if (emails != null && emails.size() > 0) {
                inputArray = emails;
                inputList = createInputList(emails, false);

            } else {
                inputArray = obj.getJsonArray("email_hash");
                inputList = createInputList(inputArray, true);
            }

            final JsonArray mapped = new JsonArray();
            final int count = inputList.length;
            for (int i = 0; i < count; ++i) {
                final InputUtil.InputVal input = inputList[i];
                if (input != null && input.isValid()) {
                    final MappedIdentity mappedIdentity = this.idService.map(input.getIdentityInput());
                    final JsonObject resp = new JsonObject();
                    resp.put("identifier", input.getProvided());
                    resp.put("advertising_id", mappedIdentity.getAdvertisingId());
                    resp.put("bucket_id", mappedIdentity.getBucketId());
                    mapped.add(resp);
                }
            }

            final JsonObject resp = new JsonObject();
            resp.put("mapped", mapped);
            ResponseUtil.Success(rc, resp);
        } catch (Exception e) {
            ResponseUtil.Error("unknown", 500, rc, "Unknown State");
            e.printStackTrace();
        }
    }

    private void handleIdentityMapBatch(RoutingContext rc) {
        try {
            final JsonObject obj = rc.getBodyAsJson();
            final InputUtil.InputVal[] inputList;
            final JsonArray emails = obj.getJsonArray("email");
            final JsonArray inputArray;
            // FIXME TODO. Avoid Double Iteration. Turn to a decorator pattern
            if (emails != null && emails.size() > 0) {
                inputArray = emails;
                inputList = createInputList(emails, false);

            } else {
                inputArray = obj.getJsonArray("email_hash");
                inputList = createInputList(inputArray, true);
            }

            final JsonArray mapped = new JsonArray();
            final int count = inputList.length;
            for (int i = 0; i < count; ++i) {
                final InputUtil.InputVal input = inputList[i];
                if (input != null && input.isValid()) {
                    final MappedIdentity mappedIdentity = this.idService.map(input.getIdentityInput());
                    final JsonObject resp = new JsonObject();
                    resp.put("identifier", input.getProvided());
                    resp.put("advertising_id", mappedIdentity.getAdvertisingId());
                    mapped.add(resp);
                }
            }

            final JsonObject resp = new JsonObject();
            resp.put("mapped", mapped);
            sendJsonResponse(rc, resp);
        } catch (Exception e) {
            e.printStackTrace();
            rc.fail(500);
        }
    }

    private InputUtil.InputVal[] createInputList(JsonArray a, boolean inputAsHash) {
        if (a == null || a.size() == 0) {
            return new InputUtil.InputVal[0];
        }
        final int size = a.size();
        final InputUtil.InputVal[] resp = new InputUtil.InputVal[size];

        for (int i = 0; i < size; ++i) {
            if (inputAsHash) {
                resp[i] = InputUtil.NormalizeHash(a.getString(i));
            } else {
                resp[i] = InputUtil.NormalizeEmail(a.getString(i));
            }
        }
        return resp;

    }

    private JsonObject toJsonV1(IdentityTokens t) {
        final JsonObject json = new JsonObject();
        json.put("advertising_token", t.getAdvertisingToken());
        json.put("user_token", t.getUserToken());
        json.put("refresh_token", t.getRefreshToken());
        return json;
    }

    private JsonArray toJson(List<EncryptionKey> keys, ClientKey clientKey, IKeyAclProvider.IKeysAclSnapshot acls) {
        final JsonArray a = new JsonArray();
        for (int i = 0; i < keys.size(); ++i) {
            final EncryptionKey k = keys.get(i);
            if(!acls.canClientAccessKey(clientKey, k)) {
                continue;
            }

            final JsonObject o = new JsonObject();
            o.put("id", k.getId());
            o.put("created", k.getCreated().getEpochSecond());
            o.put("activates", k.getActivates().getEpochSecond());
            o.put("expires", k.getExpires().getEpochSecond());
            o.put("secret", EncodingUtils.toBase64String(k.getKeyBytes()));
            o.put("site_id", k.getSiteId());
            a.add(o);
        }
        return a;
    }

    private JsonObject toJson(IdentityTokens t) {
        final JsonObject json = new JsonObject();
        json.put("advertisement_token", t.getAdvertisingToken());
        json.put("advertising_token", t.getAdvertisingToken());
        json.put("user_token", t.getUserToken());
        json.put("refresh_token", t.getRefreshToken());
        json.put("tdid", t.getTdid());

        return json;
    }

    private void sendJsonResponse(RoutingContext rc, JsonObject json) {
        rc.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .end(json.encode());
    }

    private void sendJsonResponse(RoutingContext rc, JsonArray json) {
        rc.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .end(json.encode());
    }

    public static class ResponseStatus {
        public static String Success = "success";
        public static String Unauthorized = "unauthorized";
        public static String ClientError = "client_error";
        public static String OptOut = "optout";
        public static String InvalidToken = "invalid_token";
    }

}