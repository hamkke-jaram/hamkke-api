package kr.hamkkejaram.common;

import java.io.IOException;

public class PayloadTooLargeException extends IOException {
    public PayloadTooLargeException() {
        super("request body too large");
    }
}
