package kr.hamkkejaram.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 설정값 {@code app.facilities.import-file}(환경변수 {@code APP_FACILITIES_IMPORT_FILE})에 파일이 있으면
 * 기동할 때 한 번 기관 CSV를 넣는다. 같은 파일을 다시 넣어도 바뀌는 것이 없다.
 * 파일이 틀려도 서버는 그대로 뜬다(상담 안내 같은 다른 기능을 막지 않는다). 대신 ERROR 로그를 남긴다.
 */
@Component
public class FacilityImportRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(FacilityImportRunner.class);

    private final FacilityImporter importer;
    private final String importFile;

    public FacilityImportRunner(FacilityImporter importer,
                                @Value("${app.facilities.import-file:}") String importFile) {
        this.importer = importer;
        this.importFile = importFile;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (importFile == null || importFile.isBlank()) return;
        try {
            FacilityImporter.Result r = importer.importFile(Path.of(importFile.strip()));
            log.info("facility import done: inserted={} updated={} unchanged={}", r.inserted(), r.updated(), r.unchanged());
        } catch (FacilityImportException e) {
            log.error("facility import rejected, nothing changed: {}", e.getMessage());
        } catch (IOException | RuntimeException e) {
            log.error("facility import failed, nothing changed: {}", e.getClass().getSimpleName());
        }
    }
}
