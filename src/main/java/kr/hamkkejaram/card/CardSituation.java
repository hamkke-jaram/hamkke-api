package kr.hamkkejaram.card;

import kr.hamkkejaram.record.ChangeTrend;

/** 카드를 보여줄 상황. ANY는 어느 상황에서나 쓸 수 있는 카드. */
public enum CardSituation {
    FIRST_RECORD,
    GROWING,
    STEADY,
    ANY;

    public boolean matches(ChangeTrend trend) {
        return this == ANY || this.name().equals(trend.name());
    }
}
