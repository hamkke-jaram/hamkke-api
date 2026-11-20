package kr.hamkkejaram.aiobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 두 저장소가 함께 쓰는 문장 검사 예문(docs/ai-observation.md 6장).
 *
 * <p>{@code src/test/resources/wording-cases.json}은 hamkke-ai {@code server/test/wording-cases.json}의
 * 바이트 단위 사본이다. hamkke-ai 의 검사(server/src/wording.ts)와 이 서버의 두 번째 검사
 * ({@link ObservationSentenceFilter})가 같은 예문에 같은 결과를 내는지 문장마다 확인한다(AIO-09: 같은 폭).
 * 예문은 이 파일에 옮겨 적지 않는다. 걸려야 하는 문장에는 버려야 할 표현이 들어 있기 때문이다.
 */
class ObservationWordingCasesTest {
    private static final String RESOURCE = "/wording-cases.json";
    /** 사본의 SHA-256. 원본이 바뀌면 사본을 바이트 그대로 다시 복사하고 이 값을 함께 고친다. */
    private static final String EXPECTED_SHA256 = "81becd67580a60914c6ca36138111f6792e19da611cd7db4fd12b855fb637a43";
    /** 저장소를 나란히 클론했을 때의 원본 위치(hamkke-api 에서 본 경로). */
    private static final Path ORIGINAL = Path.of("..", "hamkke-ai", "server", "test", "wording-cases.json");

    private static byte[] copyBytes() throws IOException {
        try (InputStream in = ObservationWordingCasesTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as("src/test/resources%s", RESOURCE).isNotNull();
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** 예문 하나: 번호(1부터), 갈래, 기대(지나가야 하면 true), 문장. */
    static List<Arguments> cases() throws IOException {
        JsonNode root = new ObjectMapper().readTree(copyBytes());
        List<Arguments> out = new ArrayList<>();
        int n = 0;
        for (JsonNode c : root.get("cases")) {
            out.add(Arguments.of(++n, c.get("group").asText(), c.get("pass").asBoolean(), c.get("text").asText()));
        }
        return out;
    }

    @ParameterizedTest(name = "{0} [{1}] pass={2}")
    @MethodSource("cases")
    void everyCaseGivesTheSameResultAsTheAiServer(int number, String group, boolean pass, String text) {
        assertThat(ObservationSentenceFilter.isAllowed(text))
                .as("예문 %d번 [%s] — hamkke-ai 와 결과가 달라요. 지나가야 하면 true, 걸려야 하면 false", number, group)
                .isEqualTo(pass);
    }

    @Test
    void fileHasBothKindsOfCases() throws IOException {
        List<Arguments> all = cases();
        assertThat(all).isNotEmpty();
        assertThat(all).as("지나가야 하는 문장").anyMatch(a -> (boolean) a.get()[2]);
        assertThat(all).as("걸려야 하는 문장").anyMatch(a -> !(boolean) a.get()[2]);
    }

    @Test
    void copyIsByteForByteTheAgreedFile() throws Exception {
        byte[] bytes = copyBytes();
        assertThat(sha256(bytes))
                .as("src/test/resources/wording-cases.json 이 약속한 사본과 달라요. hamkke-ai 의 "
                        + "server/test/wording-cases.json 이 바뀌었다면 그 파일을 바이트 그대로 다시 복사하고(LF, BOM 없음) "
                        + "이 테스트의 EXPECTED_SHA256 과 두 저장소의 식을 함께 고쳐 주세요. 사본만 손으로 고치면 안 돼요.")
                .isEqualTo(EXPECTED_SHA256);
        // 줄바꿈이 CRLF 로 바뀌거나 BOM 이 붙으면 해시가 달라진다. 까닭을 바로 알 수 있게 따로도 본다.
        assertThat(bytes[0] & 0xFF).as("BOM 없음(첫 바이트는 여는 중괄호)").isEqualTo('{');
        int carriageReturns = 0;
        for (byte b : bytes) {
            if (b == 0x0D) carriageReturns++;
        }
        assertThat(carriageReturns).as("줄바꿈은 LF(CR 개수)").isZero();
    }

    /** 두 저장소를 나란히 둔 개발 환경에서는 원본과도 바로 견준다. hamkke-ai 가 없으면(CI) 건너뛴다. */
    @Test
    void copyMatchesTheOriginalWhenBothRepositoriesAreCheckedOut() throws Exception {
        assumeTrue(Files.isRegularFile(ORIGINAL), "hamkke-ai 저장소가 옆에 없어서 건너뛰어요");
        assertThat(sha256(copyBytes()))
                .as("hamkke-ai 의 원본(%s)과 hamkke-api 의 사본이 달라요. 원본을 바이트 그대로 다시 복사해 주세요.", ORIGINAL)
                .isEqualTo(sha256(Files.readAllBytes(ORIGINAL)));
    }
}
