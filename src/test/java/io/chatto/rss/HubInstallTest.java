package io.chatto.rss;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HubInstallTest {
    private static final String PAYLOAD = """
            {"bot_user_id":"U1","login":"rss_hub_bot","display_name":"RSS Hub Bot",
             "api_key":"cht_BK_U1.secret","api_key_id":"legacy",
             "chatto_server_url":"https://chat.example.com/",
             "requested_permissions":["message.read"],
             "granted_permissions":["message.read","room.join"],
             "denied_permissions":{},
             "requested_rooms":["R1"],"added_rooms":[{"id":"R1","name":"general"}],
             "failed_rooms":{},"installed_at":"2026-01-01T00:00:00Z"}
            """;

    @Test
    void parsesHubCallbackPayload() {
        var install = HubInstall.parse(PAYLOAD);
        assertThat(install.botUserId()).isEqualTo("U1");
        assertThat(install.apiKey()).isEqualTo("cht_BK_U1.secret");
        assertThat(install.serverKey()).isEqualTo("https://chat.example.com");
        assertThat(install.addedRooms()).hasSize(1);
        assertThat(install.addedRooms().get(0).name()).isEqualTo("general");
    }

    @Test
    void rejectsPayloadWithoutServerUrl() {
        assertThatThrownBy(() -> HubInstall.parse(PAYLOAD.replace("https://chat.example.com/", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
