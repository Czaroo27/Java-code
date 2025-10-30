package com.lspgroup.fleet;

import org.optaplanner.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import org.optaplanner.core.api.score.stream.*;

public class FleetConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory constraintFactory) {
        return new Constraint[]{
                // HARD CONSTRAINTS - MUSZĄ być spełnione
                allRoutesMustBeAssigned(constraintFactory),
                vehicleMaxMileageLimit(constraintFactory),

                // MEDIUM CONSTRAINTS - Koszty nadprzebiegu
                overmileageCost(constraintFactory),

                // SOFT CONSTRAINTS - Optymalizacja
                minimizeRepositioningCost(constraintFactory),
                minimizeRepositioningCount(constraintFactory)
        };
    }

    /**
     * KRYTYCZNE: Każda trasa MUSI być przypisana do pojazdu
     */
    private Constraint allRoutesMustBeAssigned(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(RouteAssignment.class)
                .filter(assignment -> assignment.getVehicle() == null)
                .penalize(HardMediumSoftScore.ONE_HARD, assignment -> 1000000)
                .asConstraint("allRoutesMustBeAssigned");
    }

    /**
     * KRYTYCZNE: Pojazd nie może przekroczyć limitu + 300km
     */
    private Constraint vehicleMaxMileageLimit(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(RouteAssignment.class)
                .filter(assignment -> assignment.getVehicle() != null)
                .groupBy(
                        assignment -> assignment.getVehicle(),
                        ConstraintCollectors.sum(assignment -> (int) assignment.getDistanceKm())
                )
                .filter((vehicle, totalRouteKm) -> {
                    int totalKm = vehicle.getCurrentYearKm() + totalRouteKm;
                    int maxAllowed = vehicle.getMaxAllowedKm();
                    return totalKm > maxAllowed;
                })
                .penalize(HardMediumSoftScore.ONE_HARD,
                        (vehicle, totalRouteKm) -> {
                            int totalKm = vehicle.getCurrentYearKm() + totalRouteKm;
                            int maxAllowed = vehicle.getMaxAllowedKm();
                            return (totalKm - maxAllowed) * 10000;
                        })
                .asConstraint("vehicleMaxMileageLimit");
    }

    /**
     * KOSZT: Nadprzebieg w ramach dozwolonych 300km
     */
    private Constraint overmileageCost(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(RouteAssignment.class)
                .filter(assignment -> assignment.getVehicle() != null)
                .groupBy(
                        assignment -> assignment.getVehicle(),
                        ConstraintCollectors.sum(assignment -> (int) assignment.getDistanceKm())
                )
                .filter((vehicle, totalRouteKm) -> {
                    int totalKm = vehicle.getCurrentYearKm() + totalRouteKm;
                    int limit = vehicle.getProportionalLimit();
                    return totalKm > limit;
                })
                .penalize(HardMediumSoftScore.ONE_MEDIUM,
                        (vehicle, totalRouteKm) -> {
                            int totalKm = vehicle.getCurrentYearKm() + totalRouteKm;
                            int limit = vehicle.getProportionalLimit();
                            int overage = totalKm - limit;
                            int maxOverage = Math.min(overage, 300);
                            return maxOverage * 92; // 0.92 PLN/km × 100
                        })
                .asConstraint("overmileageCost");
    }

    /**
     * MINIMALIZUJ: Koszt przestawienia (1000 + odległość + czas)
     */
    private Constraint minimizeRepositioningCost(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(RouteAssignment.class)
                .filter(assignment -> assignment.getVehicle() != null)
                .filter(assignment -> {
                    Vehicle v = assignment.getVehicle();
                    return v.getCurrentLocationId() != -1 &&
                            v.getCurrentLocationId() != assignment.getStartLocationId();
                })
                .penalize(HardMediumSoftScore.ONE_SOFT,
                        assignment -> {
                            double cost = assignment.calculateRepositioningCost(
                                    assignment.getVehicle().getDistances());
                            return (int) cost;
                        })
                .asConstraint("minimizeRepositioningCost");
    }

    /**
     * MINIMALIZUJ: Liczba przestawień
     */
    private Constraint minimizeRepositioningCount(ConstraintFactory constraintFactory) {
        return constraintFactory
                .forEach(RouteAssignment.class)
                .filter(assignment -> assignment.getVehicle() != null)
                .filter(assignment -> {
                    Vehicle v = assignment.getVehicle();
                    return v.getCurrentLocationId() != -1 &&
                            v.getCurrentLocationId() != assignment.getStartLocationId();
                })
                .penalize(HardMediumSoftScore.ONE_SOFT, assignment -> 500)
                .asConstraint("minimizeRepositioningCount");
    }
}