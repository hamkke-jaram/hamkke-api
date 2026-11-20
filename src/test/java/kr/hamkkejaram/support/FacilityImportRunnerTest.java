package kr.hamkkejaram.support;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FacilityImportRunnerTest {

    @Test
    void doesNothingWhenNoFileIsConfigured() throws Exception {
        FacilityImporter importer = mock(FacilityImporter.class);
        new FacilityImportRunner(importer, "").run(null);
        new FacilityImportRunner(importer, "   ").run(null);
        new FacilityImportRunner(importer, null).run(null);
        verify(importer, never()).importFile(any());
    }

    @Test
    void importsTheConfiguredFileOnce() throws Exception {
        FacilityImporter importer = mock(FacilityImporter.class);
        when(importer.importFile(any())).thenReturn(new FacilityImporter.Result(3, 1, 0));
        new FacilityImportRunner(importer, " /data/facilities.csv ").run(null);
        verify(importer).importFile(Path.of("/data/facilities.csv"));
    }

    @Test
    void aBadFileDoesNotStopTheServer() throws Exception {
        FacilityImporter importer = mock(FacilityImporter.class);
        when(importer.importFile(any())).thenThrow(new FacilityImportException("2번째 줄 sido: 17개 시·도 이름이 아니에요."));
        assertThatCode(() -> new FacilityImportRunner(importer, "/data/facilities.csv").run(null)).doesNotThrowAnyException();

        FacilityImporter broken = mock(FacilityImporter.class);
        when(broken.importFile(any())).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> new FacilityImportRunner(broken, "/data/facilities.csv").run(null)).doesNotThrowAnyException();
    }
}
