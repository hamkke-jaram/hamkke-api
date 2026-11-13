package kr.hamkkejaram.aiobs;

/**
 * 영수증을 받아들일 수 없을 때. 사유는 로그·테스트용 짧은 코드이며 사용자에게 그대로 보이지 않는다.
 * 사유에는 관찰 문장이나 토큰 값을 넣지 않는다.
 */
public class InvalidReceiptException extends RuntimeException {
    private final String reason;

    public InvalidReceiptException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() { return reason; }
}
