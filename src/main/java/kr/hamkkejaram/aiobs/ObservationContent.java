package kr.hamkkejaram.aiobs;

import java.util.List;

/**
 * 관찰 기록 객체(영수증의 {@code obs} 클레임, docs/ai-observation.md 6장)를 검증한 결과.
 * summary는 없을 수 있다(null). 사진은 어디에도 없다.
 */
public record ObservationContent(String summary, List<ChildState> childStates, List<AdultResponse> adultResponses,
                                 List<Moment> moments, int peopleCount) {

    public static final int MAX_SUMMARY_CHARS = 300;
    public static final int MAX_NOTE_CHARS = 120;
    public static final int MAX_TAGS = 3;
    public static final int MAX_MOMENTS = 4;
    public static final int MAX_PEOPLE_COUNT = 3; // 3은 '3명 이상'

    /** t는 보낸 사진의 시각(초). */
    public record Moment(double t, String note) {}

    public ObservationContent {
        childStates = List.copyOf(childStates);
        adultResponses = List.copyOf(adultResponses);
        moments = List.copyOf(moments);
    }

    /** 요약도 태그도 메모도 없어 남길 것이 없는 기록. */
    public boolean isEmpty() {
        return summary == null && childStates.isEmpty() && adultResponses.isEmpty() && moments.isEmpty();
    }
}
