package kr.hamkkejaram.record;

public record MetricChanges(MetricChange jointPlay, MetricChange response, MetricChange calm) {

    public long count(MetricChange c) {
        long n = 0;
        if (jointPlay == c) n++;
        if (response == c) n++;
        if (calm == c) n++;
        return n;
    }
}
