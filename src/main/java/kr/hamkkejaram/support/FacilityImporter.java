package kr.hamkkejaram.support;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 정규화한 기관 CSV를 넣는다(SUP-04). 같은 키(유형·이름·주소)면 고치고, 없으면 더한다.
 * 파일에 없는 기관은 지우지 않는다. 한 줄이라도 틀리면 아무것도 바꾸지 않는다(한 트랜잭션).
 *
 * <p>status 열이 없을 때: 새 기관은 PUBLIC_DATA. 이미 있는 기관은 내용이 그대로면 status도 그대로 두고,
 * 내용이 바뀌면 PUBLIC_DATA로 돌린다(우리가 확인한 것은 예전 내용이기 때문이다).
 */
@Service
public class FacilityImporter {
    /** 이보다 큰 파일은 읽지 않는다. 시·도 17곳의 다섯 유형을 다 넣어도 몇 MB 안쪽이다. */
    static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private final SupportFacilityRepository repository;
    private final Clock clock;

    public FacilityImporter(SupportFacilityRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public record Result(int inserted, int updated, int unchanged) {}

    @Transactional
    public Result importFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new FacilityImportException("파일을 찾지 못했어요.");
        if (Files.size(path) > MAX_FILE_BYTES) throw new FacilityImportException("파일이 20MB보다 커요.");
        // UTF-8이 아닌 파일(예: 공공데이터 원본의 EUC-KR)은 글자가 깨진 채 들어가지 않게 바로 멈춘다.
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), decoder)) {
            return importCsv(reader);
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new FacilityImportException("UTF-8 파일이 아니에요. scripts/facilities로 먼저 바꿔 주세요.");
        }
    }

    @Transactional
    public Result importCsv(Reader reader) throws IOException {
        List<FacilityRow> rows = FacilityCsv.parse(reader);
        Map<FacilityRow.Key, SupportFacility> existing = new HashMap<>();
        for (SupportFacility f : repository.findAll()) {
            existing.put(new FacilityRow.Key(f.getCategory(), f.getName(), f.getAddress()), f);
        }
        Instant now = clock.instant();
        int inserted = 0, updated = 0, unchanged = 0;
        for (FacilityRow row : rows) {
            SupportFacility found = existing.get(row.key());
            if (found == null) {
                FacilityStatus status = row.status() != null ? row.status() : FacilityStatus.PUBLIC_DATA;
                repository.save(new SupportFacility(row, status, now));
                inserted++;
                continue;
            }
            boolean same = found.sameContentAs(row);
            FacilityStatus status = row.status() != null ? row.status()
                    : same ? found.getStatus() : FacilityStatus.PUBLIC_DATA;
            if (same && status == found.getStatus()) {
                unchanged++;
            } else {
                found.update(row, status, now);
                updated++;
            }
        }
        repository.flush();
        return new Result(inserted, updated, unchanged);
    }
}
