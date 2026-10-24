package com.amongus;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.ActionResult;
import net.minecraft.item.SwordItem;
import net.minecraft.world.GameMode;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.command.argument.IntegerArgumentType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Server-authoritative Among Us game logic.
 * All role, task, meeting and win-state decisions happen on the server.
 */
public class AmongUsMod implements ModInitializer {
    public static final String MOD_ID = "amongus";
    private static final Game GAME = new Game();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("amongus")
                .then(literal("join").executes(ctx -> GAME.join(ctx.getSource())))
                .then(literal("leave").executes(ctx -> GAME.leave(ctx.getSource())))
                .then(literal("start").requires(s -> s.hasPermissionLevel(2)).executes(ctx -> GAME.start(ctx.getSource())))
                .then(literal("end").requires(s -> s.hasPermissionLevel(2)).executes(ctx -> GAME.end(ctx.getSource())))
                .then(literal("status").executes(ctx -> GAME.status(ctx.getSource())))
                .then(literal("settings")
                    .then(literal("impostors").then(net.minecraft.server.command.CommandManager.argument("number", IntegerArgumentType.integer(1, 15))
                        .requires(src -> src.hasPermissionLevel(2)).executes(ctx -> GAME.setImpostors(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "number")))))
                    .then(literal("crewmates").then(net.minecraft.server.command.CommandManager.argument("number", IntegerArgumentType.integer(1, 100))
                        .requires(src -> src.hasPermissionLevel(2)).executes(ctx -> GAME.setCrewmates(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "number")))))
                    .then(literal("show").requires(src -> src.hasPermissionLevel(2)).executes(ctx -> GAME.showSettings(ctx.getSource()))))
                .then(literal("task").executes(ctx -> GAME.completeTask(ctx.getSource())))
                .then(literal("report").executes(ctx -> GAME.report(ctx.getSource())))
                .then(literal("meeting").executes(ctx -> GAME.callMeeting(ctx.getSource())))
                .then(literal("vote").then(net.minecraft.server.command.CommandManager.argument("player", net.minecraft.command.argument.EntityArgumentType.player())
                    .executes(ctx -> GAME.vote(ctx.getSource(), net.minecraft.command.argument.EntityArgumentType.getPlayer(ctx, "player")))))
                .then(literal("skip").executes(ctx -> GAME.skipVote(ctx.getSource())))
                .then(literal("vent").executes(ctx -> GAME.vent(ctx.getSource())))
                .then(literal("sabotage").executes(ctx -> GAME.sabotage(ctx.getSource())))
                .then(literal("help").executes(ctx -> GAME.help(ctx.getSource())))
            );
        });
        ServerTickEvents.END_SERVER_TICK.register(GAME::tick);
        AttackEntityCallback.EVENT.register((attacker, world, hand, entity, hitResult) -> {
            if (world.isClient()) return ActionResult.PASS;
            if (attacker instanceof ServerPlayerEntity serverAttacker && entity instanceof ServerPlayerEntity target) {
                return GAME.handleSwordAttack(serverAttacker, target);
            }
            return ActionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;
            if (world.getBlockState(hit.getBlockPos()).isOf(Blocks.IRON_TRAPDOOR)) return GAME.useVentAt(serverPlayer, hit.getBlockPos());
            return GAME.useTaskStation(serverPlayer, hit.getBlockPos());
        });
    }

    private enum Role { CREWMATE, IMPOSTOR }
    private enum Phase { LOBBY, PLAYING, MEETING, ENDED }

    private static final class PlayerState {
        Role role;
        boolean alive = true;
        boolean ghost;
        int tasksDone;
        final Set<Block> assignedTasks = new HashSet<>();
        final Set<Block> completedTasks = new HashSet<>();
        UUID vote;
        boolean skipped;
        PlayerState(Role role) { this.role = role; }
    }

    private static final class Game {
        private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
        private Phase phase = Phase.LOBBY;
        private int seconds;
        private int meetingTicks;
        private int sabotageTicks;
        private int totalTasks;
        private int configuredImpostors = 1;
        private int configuredCrewmates = -1;
        private final Random random = new Random();
        private final Map<UUID, Integer> killCooldowns = new HashMap<>();
        private static final List<Block> TASK_STATIONS = List.of(Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.STONECUTTER, Blocks.CARTOGRAPHY_TABLE, Blocks.ANVIL, Blocks.JUKEBOX, Blocks.LEVER, Blocks.LOOM, Blocks.GRINDSTONE, Blocks.SMITHING_TABLE);

        int join(ServerCommandSource source) {
            ServerPlayerEntity p = player(source);
            if (p == null) return fail(source, "This command must be used by a player.");
            if (phase != Phase.LOBBY) return fail(source, "The game has already started.");
            if (players.containsKey(p.getUuid())) return fail(source, "You are already in the lobby.");
            players.put(p.getUuid(), new PlayerState(Role.CREWMATE));
            broadcast(source.getServer(), p.getName().getString() + " joined (" + players.size() + " players).");
            return 1;
        }

        int leave(ServerCommandSource source) {
            ServerPlayerEntity p = player(source);
            if (p == null) return fail(source, "This command must be used by a player.");
            if (players.remove(p.getUuid()) == null) return fail(source, "You are not in the game.");
            broadcast(source.getServer(), p.getName().getString() + " left the game.");
            checkWin(source.getServer());
            return 1;
        }

        int start(ServerCommandSource source) {
            if (phase != Phase.LOBBY && phase != Phase.ENDED) return fail(source, "A game is already running.");
            if (players.size() < 4) return fail(source, "You need at least 4 players to start.");
            List<UUID> ids = new ArrayList<>(players.keySet());
            Collections.shuffle(ids);
            int impostors = configuredImpostors;
            int crewmates = configuredCrewmates < 0 ? ids.size() - impostors : configuredCrewmates;
            if (impostors + crewmates != ids.size()) return fail(source, "Your settings require " + (impostors + crewmates) + " players, but " + ids.size() + " joined.");
            if (impostors >= ids.size()) return fail(source, "You must have at least one Crewmate.");
            players.clear();
            for (int i = 0; i < ids.size(); i++) players.put(ids.get(i), new PlayerState(i < impostors ? Role.IMPOSTOR : Role.CREWMATE));
            phase = Phase.PLAYING;
            seconds = 0;
            totalTasks = 0;
            for (UUID id : ids) {
                PlayerState ps = players.get(id);
                if (ps.role == Role.CREWMATE) totalTasks += 5;
                ServerPlayerEntity p = source.getServer().getPlayerManager().getPlayer(id);
                if (p != null) {
                    p.setGameMode(GameMode.ADVENTURE);
                    p.sendMessage(Text.literal("Your role: " + ps.role.name()).formatted(ps.role == Role.IMPOSTOR ? Formatting.RED : Formatting.GREEN), false);
                    if (ps.role == Role.IMPOSTOR) p.sendMessage(Text.literal("Use /amongus sabotage. Right-click iron trapdoors to travel through vents.").formatted(Formatting.RED), false);
                    else {
                        Collections.shuffle(new ArrayList<>(TASK_STATIONS));
                        List<Block> choices = new ArrayList<>(TASK_STATIONS);
                        Collections.shuffle(choices);
                        ps.assignedTasks.addAll(choices.subList(0, 5));
                        p.sendMessage(Text.literal("Your tasks: " + taskNames(ps) + ". Right-click the matching stations around the map.").formatted(Formatting.GREEN), false);
                    }
                }
            }
            broadcast(source.getServer(), "Among Us has started! " + impostors + " impostor(s).");
            return 1;
        }

        int end(ServerCommandSource source) {
            reset(source.getServer(), "The game was ended by an operator.");
            return 1;
        }

        int status(ServerCommandSource source) {
            source.sendFeedback(() -> Text.literal("Among Us | " + phase + " | Players: " + players.size() + " | Time: " + seconds + "s"), false);
            return 1;
        }

        int setImpostors(ServerCommandSource source, int count) {
            if (phase != Phase.LOBBY && phase != Phase.ENDED) return fail(source, "Change settings only in the lobby.");
            configuredImpostors = count;
            return showSettings(source);
        }

        int setCrewmates(ServerCommandSource source, int count) {
            if (phase != Phase.LOBBY && phase != Phase.ENDED) return fail(source, "Change settings only in the lobby.");
            configuredCrewmates = count;
            return showSettings(source);
        }

        int showSettings(ServerCommandSource source) {
            String crew = configuredCrewmates < 0 ? "automatic (joined players minus impostors)" : String.valueOf(configuredCrewmates);
            source.sendFeedback(() -> Text.literal("Among Us settings | Impostors: " + configuredImpostors + " | Crewmates: " + crew), false);
            return 1;
        }

        int completeTask(ServerCommandSource source) {
            PlayerState ps = get(source);
            if (phase != Phase.PLAYING || ps == null || !ps.alive) return fail(source, "You cannot do a task right now.");
            if (ps.role != Role.CREWMATE) return fail(source, "Impostors cannot complete crew tasks.");
            return fail(source, "Tasks are completed by right-clicking the assigned task station blocks in the world.");
        }

        int report(ServerCommandSource source) {
            PlayerState ps = get(source);
            if (phase != Phase.PLAYING || ps == null || !ps.alive) return fail(source, "You cannot report right now.");
            openMeeting(source.getServer(), player(source).getName().getString() + " called a body report.");
            return 1;
        }

        int callMeeting(ServerCommandSource source) {
            PlayerState ps = get(source);
            if (phase != Phase.PLAYING || ps == null || !ps.alive) return fail(source, "You cannot call a meeting right now.");
            openMeeting(source.getServer(), player(source).getName().getString() + " called an emergency meeting.");
            return 1;
        }

        int vote(ServerCommandSource source, ServerPlayerEntity target) {
            if (phase != Phase.MEETING) return fail(source, "Voting is not open.");
            PlayerState voter = get(source), candidate = players.get(target.getUuid());
            if (voter == null || !voter.alive || candidate == null || !candidate.alive) return fail(source, "Invalid voter or target.");
            voter.vote = target.getUuid();
            voter.skipped = false;
            source.sendFeedback(() -> Text.literal("Vote submitted for " + target.getName().getString() + "."), false);
            resolveIfEveryoneVoted(source.getServer());
            return 1;
        }

        int skipVote(ServerCommandSource source) {
            if (phase != Phase.MEETING) return fail(source, "Voting is not open.");
            PlayerState voter = get(source);
            if (voter == null || !voter.alive) return fail(source, "You cannot vote.");
            voter.vote = null;
            voter.skipped = true;
            source.sendFeedback(() -> Text.literal("You skipped."), false);
            resolveIfEveryoneVoted(source.getServer());
            return 1;
        }

        int vent(ServerCommandSource source) {
            PlayerState ps = get(source);
            if (phase != Phase.PLAYING || ps == null || !ps.alive || ps.role != Role.IMPOSTOR) return fail(source, "Only living impostors can use vents.");
            ServerPlayerEntity p = player(source);
            BlockPos here = p.getBlockPos();
            BlockPos nearest = null;
            double best = 25.0;
            for (int x = -4; x <= 4; x++) for (int y = -2; y <= 2; y++) for (int z = -4; z <= 4; z++) {
                BlockPos pos = here.add(x, y, z);
                if (p.getWorld().getBlockState(pos).isOf(Blocks.IRON_TRAPDOOR)) {
                    double d = p.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (d < best) { best = d; nearest = pos.toImmutable(); }
                }
            }
            if (nearest == null) return fail(source, "No vent nearby. Place iron trapdoors as vent entrances.");
            List<BlockPos> vents = new ArrayList<>();
            BlockPos center = nearest;
            for (int x = -32; x <= 32; x++) for (int y = -8; y <= 8; y++) for (int z = -32; z <= 32; z++) {
                BlockPos pos = center.add(x, y, z);
                if (p.getWorld().getBlockState(pos).isOf(Blocks.IRON_TRAPDOOR)) vents.add(pos.toImmutable());
            }
            vents.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(center)));
            if (vents.size() < 2) return fail(source, "Place at least two iron trapdoors within 32 blocks to connect vents.");
            int index = vents.indexOf(nearest);
            BlockPos destination = vents.get((index + 1) % vents.size());
            p.requestTeleport(destination.getX() + 0.5, destination.getY() + 1.0, destination.getZ() + 0.5);
            broadcast(source.getServer(), "An impostor used a vent!");
            return 1;
        }

        ActionResult handleSwordAttack(ServerPlayerEntity attacker, ServerPlayerEntity target) {
            PlayerState killer = players.get(attacker.getUuid());
            PlayerState victim = players.get(target.getUuid());
            if (phase != Phase.PLAYING || killer == null || victim == null || !killer.alive || !victim.alive) return ActionResult.PASS;
            // Player-versus-player damage is disabled for active Among Us participants.
            if (killer.role != Role.IMPOSTOR || victim.role != Role.CREWMATE) return ActionResult.SUCCESS;
            if (!(attacker.getMainHandStack().getItem() instanceof SwordItem)) {
                attacker.sendMessage(Text.literal("You must use a sword to eliminate a crewmate.").formatted(Formatting.RED), true);
                return ActionResult.SUCCESS;
            }
            int cooldown = killCooldowns.getOrDefault(attacker.getUuid(), 0);
            if (cooldown > 0) {
                attacker.sendMessage(Text.literal("Kill cooldown: " + ((cooldown + 19) / 20) + "s").formatted(Formatting.RED), true);
                return ActionResult.SUCCESS;
            }
            if (attacker.squaredDistanceTo(target) > 9.0) {
                attacker.sendMessage(Text.literal("Get within 3 blocks to eliminate a crewmate.").formatted(Formatting.RED), true);
                return ActionResult.SUCCESS;
            }
            victim.alive = false;
            victim.ghost = true;
            target.setGameMode(GameMode.SPECTATOR);
            target.sendMessage(Text.literal("You are now a ghost. You can still observe, but cannot vote.").formatted(Formatting.GRAY), false);
            killCooldowns.put(attacker.getUuid(), 20 * 25);
            broadcast(attacker.getServer(), "A player has been eliminated. A crewmate can report the body with /amongus report.");
            checkWin(attacker.getServer());
            return ActionResult.SUCCESS;
        }

        ActionResult useVentAt(ServerPlayerEntity player, BlockPos entrance) {
            PlayerState ps = players.get(player.getUuid());
            if (ps == null || phase != Phase.PLAYING || !ps.alive || ps.role != Role.IMPOSTOR) {
                player.sendMessage(Text.literal("Only living impostors can use vents.").formatted(Formatting.RED), true);
                return ActionResult.SUCCESS;
            }
            List<BlockPos> vents = new ArrayList<>();
            BlockPos center = entrance.toImmutable();
            for (int x = -32; x <= 32; x++) for (int y = -8; y <= 8; y++) for (int z = -32; z <= 32; z++) {
                BlockPos pos = center.add(x, y, z);
                if (player.getWorld().getBlockState(pos).isOf(Blocks.IRON_TRAPDOOR)) vents.add(pos.toImmutable());
            }
            vents.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(center)));
            if (vents.size() < 2) {
                player.sendMessage(Text.literal("Connect at least two iron trapdoors within 32 blocks to make a vent network.").formatted(Formatting.RED), true);
                return ActionResult.SUCCESS;
            }
            int index = vents.indexOf(center);
            BlockPos destination = vents.get((index + 1) % vents.size());
            player.requestTeleport(destination.getX() + 0.5, destination.getY() + 1.0, destination.getZ() + 0.5);
            player.sendMessage(Text.literal("Vent travel complete.").formatted(Formatting.RED), true);
            return ActionResult.SUCCESS;
        }

        ActionResult useTaskStation(ServerPlayerEntity player, BlockPos pos) {
            PlayerState ps = players.get(player.getUuid());
            if (ps == null || phase != Phase.PLAYING || !ps.alive || ps.role != Role.CREWMATE) return ActionResult.PASS;
            Block block = player.getWorld().getBlockState(pos).getBlock();
            if (!ps.assignedTasks.contains(block)) return ActionResult.PASS;
            if (!ps.completedTasks.add(block)) {
                player.sendMessage(Text.literal("You already completed this station task.").formatted(Formatting.GRAY), true);
                return ActionResult.SUCCESS;
            }
            ps.tasksDone = ps.completedTasks.size();
            player.sendMessage(Text.literal("Task complete! " + ps.tasksDone + "/5").formatted(Formatting.GREEN), true);
            int done = players.values().stream().filter(x -> x.role == Role.CREWMATE).mapToInt(x -> x.tasksDone).sum();
            int required = (int) players.values().stream().filter(x -> x.role == Role.CREWMATE).count() * 5;
            broadcast(player.getServer(), "Task progress: " + done + "/" + required);
            if (required > 0 && done >= required) reset(player.getServer(), "Crewmates win: all tasks completed!");
            return ActionResult.SUCCESS;
        }

        private String taskNames(PlayerState ps) {
            return ps.assignedTasks.stream().map(AmongUsMod::stationName).reduce((a, b) -> a + ", " + b).orElse("none");
        }
        private static String stationName(Block block) {
            String raw = net.minecraft.registry.Registries.BLOCK.getId(block).getPath();
            return raw.replace('_', ' ');
        }

        int sabotage(ServerCommandSource source) {
            PlayerState ps = get(source);
            if (phase != Phase.PLAYING || ps == null || !ps.alive || ps.role != Role.IMPOSTOR) return fail(source, "Only living impostors can sabotage.");
            if (sabotageTicks > 0) return fail(source, "A sabotage is already active.");
            sabotageTicks = 45 * 20;
            broadcast(source.getServer(), "SABOTAGE! Crewmates have 45 seconds to resolve it. (Prototype timer)");
            return 1;
        }

        void tick(MinecraftServer server) {
            killCooldowns.replaceAll((id, ticks) -> Math.max(0, ticks - 1));
            killCooldowns.values().removeIf(ticks -> ticks <= 0);
            if (phase == Phase.PLAYING && server.getTicks() % 20 == 0) {
                seconds++;
                if (sabotageTicks > 0) {
                    sabotageTicks--;
                    if (sabotageTicks == 0) reset(server, "Impostors win: sabotage timer expired!");
                }
                if (seconds % 30 == 0) checkWin(server);
            } else if (phase == Phase.MEETING) {
                meetingTicks--;
                if (meetingTicks <= 0) finishMeeting(server);
            }
        }

        private void openMeeting(MinecraftServer server, String reason) {
            if (phase != Phase.PLAYING) return;
            phase = Phase.MEETING;
            meetingTicks = 20 * 45;
            players.values().forEach(p -> { p.vote = null; p.skipped = false; });
            broadcast(server, "MEETING: " + reason + " Vote with /amongus vote <player> or /amongus skip. 45 seconds.");
        }

        private void resolveIfEveryoneVoted(MinecraftServer server) {
            long living = players.values().stream().filter(p -> p.alive).count();
            long voted = players.values().stream().filter(p -> p.alive && (p.vote != null || p.skipped)).count();
            if (living > 0 && voted >= living) finishMeeting(server);
        }

        private void finishMeeting(MinecraftServer server) {
            if (phase != Phase.MEETING) return;
            Map<UUID, Integer> counts = new HashMap<>();
            for (PlayerState ps : players.values()) if (ps.alive && ps.vote != null) counts.merge(ps.vote, 1, Integer::sum);
            int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            List<UUID> winners = counts.entrySet().stream().filter(e -> e.getValue() == max).map(Map.Entry::getKey).toList();
            if (max > 0 && winners.size() == 1) {
                UUID out = winners.get(0);
                PlayerState ejected = players.get(out);
                if (ejected != null) {
                    ejected.alive = false; ejected.ghost = true;
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(out);
                    if (p != null) { p.setGameMode(GameMode.SPECTATOR); p.sendMessage(Text.literal("You are now a ghost.").formatted(Formatting.GRAY), false); }
                    broadcast(server, "A player was ejected. Their role was " + ejected.role + ".");
                }
            } else broadcast(server, "No one was ejected (tie or skip).");
            phase = Phase.PLAYING;
            checkWin(server);
        }

        private void checkWin(MinecraftServer server) {
            if (phase != Phase.PLAYING) return;
            long crew = players.values().stream().filter(p -> p.alive && p.role == Role.CREWMATE).count();
            long impostor = players.values().stream().filter(p -> p.alive && p.role == Role.IMPOSTOR).count();
            if (impostor == 0) reset(server, "Crewmates win: all impostors were found!");
            else if (impostor >= crew) reset(server, "Impostors win: they reached parity!");
        }

        private ServerPlayerEntity player(ServerCommandSource source) {
            try { return source.getPlayer(); }
            catch (Exception ignored) { return null; }
        }

        private PlayerState get(ServerCommandSource source) {
            ServerPlayerEntity player = player(source);
            return player == null ? null : players.get(player.getUuid());
        }

        private void reset(MinecraftServer server, String message) {
            broadcast(server, message);
            for (UUID id : players.keySet()) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                if (p != null) p.setGameMode(GameMode.SURVIVAL);
            }
            players.clear();
            phase = Phase.LOBBY;
            seconds = 0; sabotageTicks = 0; meetingTicks = 0; killCooldowns.clear();
        }

        private void broadcast(MinecraftServer server, String message) {
            server.getPlayerManager().broadcast(Text.literal("[Among Us] " + message).formatted(Formatting.AQUA), false);
        }
        private int fail(ServerCommandSource source, String message) {
            source.sendError(Text.literal("[Among Us] " + message)); return 0;
        }
        int help(ServerCommandSource source) {
            source.sendFeedback(() -> Text.literal("Commands: /amongus join, leave, start, end, status, settings impostors <number>, settings crewmates <number>, settings show, report, meeting, vote <player>, skip, vent, sabotage. Tasks: right-click assigned stations. Vents: use connected iron trapdoors."), false);
            return 1;
        }
    }
}
