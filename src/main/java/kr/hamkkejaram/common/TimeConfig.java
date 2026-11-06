package kr.hamkkejaram.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class TimeConfig {
    /** 서비스 기준 지역 시간(만 나이·주간 묶음 계산용). 저장은 UTC. */
    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
