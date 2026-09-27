package xyz.zcraft.ostella.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Response {
    private boolean success;
    private String message;
    private JsonElement data;

    public static Response error(String message, ErrorCode errorCode) {
        return new Response(false, message, errorCode.toJson());
    }

    @Override
    public String toString() {
        final JsonObject obj = new JsonObject();
        obj.addProperty("success", success);
        obj.addProperty("message", message);
        obj.add("data", responseData());

        return obj.toString();
    }

    private JsonElement responseData() {
        if (data != null && data.isJsonArray()) {
            JsonObject result = new JsonObject();
            result.add("result", data);
            return result;
        }
        return data;
    }
}
