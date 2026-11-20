package kr.hamkkejaram.support;

/** 기관 CSV를 받아들일 수 없을 때. 메시지에는 줄 번호·열 이름·이유만 담고 셀 값은 담지 않는다. */
public class FacilityImportException extends RuntimeException {
    public FacilityImportException(String message) {
        super(message);
    }

    static FacilityImportException at(int line, String column, String reason) {
        return new FacilityImportException(line + "번째 줄 " + column + ": " + reason);
    }
}
