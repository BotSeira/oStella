package xyz.zcraft.ostella.network.controller;

import com.google.gson.Gson;
import io.javalin.http.Context;
import xyz.zcraft.ostella.network.Response;
import xyz.zcraft.ostella.whatif.WhatIfEstimates;
import xyz.zcraft.ostella.whatif.WhatIfService;
import java.time.Instant;

public final class WhatIfController {
    private static final Gson GSON = new Gson();
    private final WhatIfService service;

    public WhatIfController(WhatIfService service) { this.service = service; }

    public void estimate(Context ctx) {
        try {
            if (ctx.queryParams("pp").size() > 1 || ctx.queryParams("rank").size() > 1)
                throw new IllegalArgumentException("查询参数不能重复。");
            String pp = ctx.queryParam("pp"), rank = ctx.queryParam("rank");
            Double requestedPp = pp == null ? null : Double.valueOf(pp);
            Long requestedRank = rank == null ? null : Long.valueOf(rank);
            WhatIfEstimates.validate(requestedPp, requestedRank);
            var result = WhatIfEstimates.estimate(service.current(), requestedPp, requestedRank, Instant.now());
            ctx.contentType("application/json").result(new Response(true, "Success", GSON.toJsonTree(result)).toString());
        } catch (IllegalArgumentException e) {
            ctx.status(400).contentType("application/json").result(new Response(false,
                    "请提供一个正数 pp 或正整数 rank，不能同时指定。", null).toString());
        }
    }
}
