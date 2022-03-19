package inside.data.entity;

import inside.data.annotation.Column;
import inside.data.annotation.Entity;
import inside.data.annotation.Table;
import inside.data.entity.base.GuildEntity;
import org.immutables.value.Value;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Entity
@Table(name = "guild_config")
@Value.Immutable
public interface GuildConfig extends GuildEntity {

    static ImmutableGuildConfig.Builder builder() {
        return ImmutableGuildConfig.builder();
    }

    static String formatPrefix(String prefix){
        Objects.requireNonNull(prefix, "prefix");
        if(prefix.chars().filter(Character::isLetter).count() >= 2 || prefix.length() > 4){
            return prefix + " ";
        }
        return prefix;
    }

    @Column
    ZoneId timezone();

    @Column
    Locale locale();

    @Column
    List<String> prefixes();
}
