package inside.data.service.impl;

import discord4j.common.util.Snowflake;
import inside.Settings;
import inside.data.entity.*;
import inside.data.service.EntityRetriever;
import inside.data.service.actions.*;
import inside.data.service.api.Store;
import inside.util.LocaleUtil;
import org.joda.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class EntityRetrieverImpl implements EntityRetriever{

    private final Store store;

    private final Settings settings;

    public EntityRetrieverImpl(@Autowired Store store, @Autowired Settings settings){
        this.store = store;
        this.settings = settings;
    }

    @Override
    public Mono<GuildConfig> getGuildConfigById(Snowflake guildId){
        return Mono.from(store.execute(ReadStoreActions.getGuildConfigById(guildId.asLong())));
    }

    @Override
    public Mono<Void> save(GuildConfig guildConfig){
        return Mono.from(store.execute(UpdateStoreActions.guildConfigSave(guildConfig)));
    }

    @Override
    public Mono<AdminConfig> getAdminConfigById(Snowflake guildId){
        return Mono.from(store.execute(ReadStoreActions.getAdminConfigById(guildId.asLong())));
    }

    @Override
    public Mono<Void> save(AdminConfig adminConfig){
        return Mono.from(store.execute(UpdateStoreActions.adminConfigSave(adminConfig)));
    }

    @Override
    public Mono<AuditConfig> getAuditConfigById(Snowflake guildId){
        return Mono.from(store.execute(ReadStoreActions.getAuditConfigById(guildId.asLong())));
    }

    @Override
    public Mono<Void> save(AuditConfig auditConfig){
        return Mono.from(store.execute(UpdateStoreActions.auditConfigSave(auditConfig)));
    }

    @Override
    public Mono<LocalMember> getLocalMemberById(Snowflake userId, Snowflake guildId){
        return Mono.from(store.execute(ReadStoreActions.getLocalMemberById(userId.asLong(), guildId.asLong())));
    }

    @Override
    public Mono<Void> save(LocalMember localMember){
        return Mono.from(store.execute(UpdateStoreActions.localMemberSave(localMember)));
    }

    @Override
    public Mono<GuildConfig> createGuildConfig(Snowflake guildId){
        return Mono.defer(() -> {
            GuildConfig guildConfig = new GuildConfig();
            guildConfig.guildId(guildId);
            guildConfig.prefix(settings.getDefaults().getPrefix());
            guildConfig.locale(LocaleUtil.getDefaultLocale());
            guildConfig.timeZone(settings.getDefaults().getTimeZone());
            return save(guildConfig).thenReturn(guildConfig);
        });
    }

    @Override
    public Mono<AdminConfig> createAdminConfig(Snowflake guildId){
        return Mono.defer(() -> {
            AdminConfig adminConfig = new AdminConfig();
            adminConfig.guildId(guildId);
            adminConfig.maxWarnCount(settings.getDefaults().getMaxWarnings());
            adminConfig.muteBaseDelay(Duration.millis(settings.getDefaults().getMuteEvade().toMillis())); // TODO: use joda or java.time?
            adminConfig.warnExpireDelay(Duration.millis(settings.getDefaults().getWarnExpire().toMillis()));
            return save(adminConfig).thenReturn(adminConfig);
        });
    }

    @Override
    public Mono<AuditConfig> createAuditConfig(Snowflake guildId){
        return Mono.defer(() -> {
            AuditConfig auditConfig = new AuditConfig();
            auditConfig.guildId(guildId);
            return save(auditConfig).thenReturn(auditConfig);
        });
    }

    @Override
    public Mono<LocalMember> createLocalMember(Snowflake userId, Snowflake guildId, String effectiveNickname){
        return Mono.defer(() -> {
            LocalMember localMember = new LocalMember();
            localMember.userId(userId);
            localMember.guildId(guildId);
            localMember.effectiveName(effectiveNickname);
            return save(localMember).thenReturn(localMember);
        });
    }
}
