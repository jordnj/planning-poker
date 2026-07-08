package com.planningpoker.service;

import com.planningpoker.dto.Dtos.StatsDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** TST-1: statistics rules of FR-21 / FR-26. */
class StatisticsServiceTest {

    private final StatisticsService service = new StatisticsService();

    @Test
    void computesNumericStats() {
        StatsDto stats = service.compute(List.of("3", "5", "5", "8"));
        assertThat(stats.totalVotes()).isEqualTo(4);
        assertThat(stats.numericVotes()).isEqualTo(4);
        assertThat(stats.min()).isEqualTo(3.0);
        assertThat(stats.max()).isEqualTo(8.0);
        assertThat(stats.average()).isEqualTo(5.25);
        assertThat(stats.median()).isEqualTo(5.0);
        assertThat(stats.consensus()).isFalse();
        assertThat(stats.distribution()).containsEntry("5", 2).containsEntry("3", 1).containsEntry("8", 1);
    }

    @Test
    void medianOfEvenCountAveragesMiddleValues() {
        StatsDto stats = service.compute(List.of("2", "3", "5", "8"));
        assertThat(stats.median()).isEqualTo(4.0);
    }

    @Test
    void nonNumericVotesAreExcludedFromNumericStatsButCounted() {
        StatsDto stats = service.compute(List.of("5", "8", "?", "☕"));
        assertThat(stats.totalVotes()).isEqualTo(4);
        assertThat(stats.numericVotes()).isEqualTo(2);
        assertThat(stats.average()).isEqualTo(6.5);
        assertThat(stats.nonNumeric()).containsEntry("?", 1).containsEntry("☕", 1);
        assertThat(stats.distribution()).containsEntry("?", 1);
    }

    @Test
    void halfCardParsesAsPointFive() {
        StatsDto stats = service.compute(List.of("½", "1"));
        assertThat(stats.numericVotes()).isEqualTo(2);
        assertThat(stats.min()).isEqualTo(0.5);
        assertThat(stats.average()).isEqualTo(0.75);
    }

    @Test
    void tShirtDeckHasNoNumericStats() {
        StatsDto stats = service.compute(List.of("M", "M", "L"));
        assertThat(stats.numericVotes()).isZero();
        assertThat(stats.average()).isNull();
        assertThat(stats.median()).isNull();
        assertThat(stats.consensus()).isFalse();
    }

    @Test
    void consensusWhenAllVotesMatch() {
        assertThat(service.compute(List.of("8", "8", "8")).consensus()).isTrue();
        assertThat(service.compute(List.of("M", "M")).consensus()).isTrue();
        assertThat(service.compute(List.of()).consensus()).isFalse();
    }
}
