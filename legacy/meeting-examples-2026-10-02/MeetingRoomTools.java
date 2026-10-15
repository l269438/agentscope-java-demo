package dev.yiyan.agentscope.permission;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 自制会议室与内存预约；不连接办公系统。身份须来自后端认证。 */
public final class MeetingRoomTools {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private record Room(int seats, String equipment, Set<String> members) {}
    private static final Map<String, Room> ROOMS = Map.of(
            "星河", new Room(6, "投影仪、白板", Set.of("alice", "bob")),
            "远山", new Room(12, "视频会议屏", Set.of("bob")));
    private final Clock clock;
    private final Map<String, String> reservations = new ConcurrentHashMap<>();

    public MeetingRoomTools() { this(Clock.system(ZONE)); }
    public MeetingRoomTools(Clock clock) { this.clock = clock.withZone(ZONE); }
    public int reservationCount() { return reservations.size(); }

    @Tool(name = "room_info", description = "查询当前用户有权使用的会议室容量和设备",
            readOnly = true, concurrencySafe = true)
    public Map<String, Object> roomInfo(
            @ToolParam(name = "room", description = "会议室名称，例如星河") String room,
            RuntimeContext context) {
        Room data = requireRoom(room, context);
        return Map.of("room", room, "seats", data.seats(), "equipment", data.equipment());
    }

    @Tool(name = "reserve_room", description = "创建模拟预约：北京时间未来9至17点整，固定一小时",
            readOnly = false, concurrencySafe = true)
    public Map<String, Object> reserveRoom(
            @ToolParam(name = "room", description = "会议室名称，例如星河") String room,
            @ToolParam(name = "start", description = "北京时间ISO格式，须为未来时间") String start,
            RuntimeContext context) {
        requireRoom(room, context);
        LocalDateTime time = LocalDateTime.parse(start);
        if (time.getMinute() != 0 || time.getSecond() != 0 || time.getNano() != 0
                || time.getHour() < 9 || time.getHour() >= 18
                || !time.isAfter(LocalDateTime.now(clock))) {
            throw new IllegalArgumentException("预约须为未来9至17点整，固定一小时");
        }
        String slot = room + ":" + time;
        String owner = context.getUserId();
        if (reservations.putIfAbsent(slot, owner) != null) {
            throw new IllegalStateException("该时段已有模拟预约");
        }
        return Map.of("status", "模拟预约已创建", "room", room,
                "start", time.toString(), "end", time.plusHours(1).toString(), "userId", owner);
    }

    private Room requireRoom(String room, RuntimeContext context) {
        if (room == null || room.isBlank()) throw new IllegalArgumentException("会议室名称不能为空");
        Room data = ROOMS.get(room);
        String userId = context == null ? null : context.getUserId();
        if (data == null || userId == null || !data.members().contains(userId)) {
            throw new SecurityException("当前用户无权使用该会议室");
        }
        return data;
    }
}
