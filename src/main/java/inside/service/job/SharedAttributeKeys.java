package inside.service.job;

import discord4j.common.util.Snowflake;
import io.netty.util.AttributeKey;

import static io.netty.util.AttributeKey.valueOf;

final class SharedAttributeKeys {

    private SharedAttributeKeys() {
    }

    static AttributeKey<Long> ID = valueOf("id");

    static AttributeKey<Snowflake> USER_ID = valueOf("user_id");

    static AttributeKey<Snowflake> CHANNEL_ID = valueOf("channel_id");

    static AttributeKey<String> MESSAGE = valueOf("message");
}
