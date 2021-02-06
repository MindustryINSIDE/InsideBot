package inside.data.service.impl;

import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.*;
import discord4j.rest.util.Permission;
import inside.data.entity.*;
import inside.data.repository.AdminActionRepository;
import inside.data.service.*;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;

import java.util.*;

@Service
public class AdminServiceImpl implements AdminService{

    private final AdminActionRepository repository;

    private final EntityRetriever entityRetriever;

    public AdminServiceImpl(@Autowired AdminActionRepository repository, @Autowired EntityRetriever entityRetriever){
        this.repository = repository;
        this.entityRetriever = entityRetriever;
    }

    @Override
    @Transactional(readOnly = true)
    public Flux<AdminAction> get(AdminActionType type, Snowflake guildId, Snowflake targetId){
        return Flux.fromIterable(repository.findAdminActionsByTypeAndTargetId(type, guildId, targetId));
    }

    @Override
    @Transactional
    public Mono<Void> kick(LocalMember admin, LocalMember target, String reason){
        AdminAction action = new AdminAction(target.guildId())
                .type(AdminActionType.kick)
                .admin(admin)
                .target(target)
                .timestamp(Calendar.getInstance())
                .reason(reason);

        return Mono.just(action).doOnNext(repository::save).then();
    }

    @Override
    @Transactional
    public Mono<Void> ban(LocalMember admin, LocalMember target, String reason){
        AdminAction action = new AdminAction(target.guildId())
                .type(AdminActionType.ban)
                .admin(admin)
                .target(target)
                .timestamp(Calendar.getInstance())
                .reason(reason);

        return Mono.just(action).doOnNext(repository::save).then();
    }

    @Override
    @Transactional
    public Mono<Void> unban(Snowflake guildId, Snowflake targetId){
        AdminAction action = repository.findAdminActionsByTypeAndTargetId(AdminActionType.ban, guildId, targetId).get(0);
        return Mono.justOrEmpty(action).doOnNext(repository::delete).then();
    }

    @Override
    @Transactional
    public Mono<Void> mute(LocalMember admin, LocalMember target, Calendar end, String reason){
        AdminAction action = new AdminAction(target.guildId())
                .type(AdminActionType.mute)
                .admin(admin)
                .target(target)
                .timestamp(Calendar.getInstance())
                .end(end)
                .reason(reason);

        return Mono.just(action).doOnNext(repository::save).then();
    }

    @Override
    public Mono<Boolean> isMuted(Snowflake guildId, Snowflake targetId){
        return get(AdminActionType.mute, guildId, targetId).count().map(c -> c > 0);
    }

    @Override
    @Transactional
    public Mono<Void> unmute(Snowflake guildId, Snowflake targetId){
        AdminAction action = repository.findAdminActionsByTypeAndTargetId(AdminActionType.mute, guildId, targetId).get(0);
        return Mono.justOrEmpty(action).doOnNext(repository::delete).then();
    }

    @Override
    @Transactional
    public Mono<Void> warn(LocalMember admin, LocalMember target, String reason){
        AdminAction action = new AdminAction(target.guildId())
                .type(AdminActionType.warn)
                .admin(admin)
                .target(target)
                .timestamp(Calendar.getInstance())
                .end(DateTime.now().plusDays(20).toCalendar(entityRetriever.locale(admin.guildId())))
                .reason(reason);

        return Mono.just(action).doOnNext(repository::save).then();
    }

    @Override
    @Transactional
    public Mono<Void> unwarn(Snowflake guildId, Snowflake targetId, int index){
        AdminAction action = repository.findAdminActionsByTypeAndTargetId(AdminActionType.warn, guildId, targetId).get(index);
        return Mono.justOrEmpty(action).doOnNext(repository::delete).then();
    }

    @Override
    @Transactional(readOnly = true)
    public Flux<AdminAction> warnings(Snowflake guildId, Snowflake targetId){
        return get(AdminActionType.warn, guildId, targetId);
    }

    @Override
    @Scheduled(cron = "0 */3 * * * *")
    public void monitor(){
        Flux.fromIterable(repository.findAllByType(AdminActionType.warn))
                .filter(AdminAction::isEnd)
                .subscribe(repository::delete);
    }

    @Override
    public boolean isAdmin(Member member){
        if(member == null || entityRetriever.existsGuildById(member.getGuildId())) return false;
        GuildConfig config = entityRetriever.getGuildById(member.getGuildId());

        boolean permissed = !config.adminRoleIdsAsList().isEmpty() &&
                            member.getRoles().map(Role::getId)
                                  .any(r -> config.adminRoleIdsAsList().contains(r))
                                  .blockOptional()
                                  .orElse(false);

        boolean admin =  member.getRoles().map(Role::getPermissions)
                               .any(r -> r.contains(Permission.ADMINISTRATOR))
                               .blockOptional().orElse(false);

        return isOwner(member) || admin || permissed;
    }

    @Override
    public boolean isOwner(Member member){
        return member != null && member.getGuild().map(Guild::getOwnerId).map(s -> member.getId().equals(s))
                                       .blockOptional().orElse(false);
    }
}
