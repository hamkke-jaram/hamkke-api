package kr.hamkkejaram.talk;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.hamkkejaram.user.Role;

import java.time.Instant;

public final class TalkDtos {
    private TalkDtos() {}

    /** observationId: 선생님 관찰 메모에 단 답이면 그 메모 id. */
    public record SendMessageRequest(@NotBlank @Size(max = 500) String content, Long observationId) {}

    public record ReadRequest(@NotNull Long lastReadId) {}

    /** mine: 내가 쓴 것인지(화면에서 오른쪽에 놓는다). */
    public record MessageResponse(long id, String content, Role authorRole, String authorName, boolean mine,
                                  Long observationId, Instant createdAt) {}

    /** 안 읽은 이야기 수(남이 쓴 것만)와 가장 최근 이야기 id(없으면 null). */
    public record UnreadResponse(long unread, Long latestId) {}
}
