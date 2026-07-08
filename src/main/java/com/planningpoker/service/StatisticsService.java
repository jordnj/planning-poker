package com.planningpoker.service;

import com.planningpoker.dto.Dtos.StatsDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Round statistics (FR-21). Numeric statistics are computed only over
 * numeric cards; non-numeric votes (?, ☕, T-shirt sizes, ...) are counted
 * separately and excluded from min/max/average/median (FR-26).
 */
@Service
public class StatisticsService {

    public StatsDto compute(List<String> cardValues) {
        Map<String, Integer> distribution = new LinkedHashMap<>();
        Map<String, Integer> nonNumeric = new LinkedHashMap<>();
        List<Double> numeric = new ArrayList<>();

        for (String value : cardValues) {
            distribution.merge(value, 1, Integer::sum);
            Double parsed = parseNumeric(value);
            if (parsed != null) {
                numeric.add(parsed);
            } else {
                nonNumeric.merge(value, 1, Integer::sum);
            }
        }

        Double min = null, max = null, average = null, median = null;
        if (!numeric.isEmpty()) {
            List<Double> sorted = numeric.stream().sorted().toList();
            min = sorted.get(0);
            max = sorted.get(sorted.size() - 1);
            average = round2(sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0));
            int n = sorted.size();
            median = round2(n % 2 == 1
                    ? sorted.get(n / 2)
                    : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0);
        }

        boolean consensus = !cardValues.isEmpty()
                && cardValues.stream().distinct().count() == 1;

        return new StatsDto(cardValues.size(), numeric.size(), min, max, average, median,
                distribution, nonNumeric, consensus);
    }

    /** Returns the numeric value of a card, or null for non-numeric cards. */
    public Double parseNumeric(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.equals("½")) {
            return 0.5;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
