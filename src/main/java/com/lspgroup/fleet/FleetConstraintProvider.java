package com.lspgroup.fleet;

import org.optaplanner.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import org.optaplanner.core.api.score.stream.*;

public class FleetConstraintProvider implements ConstraintProvider {

    public FleetConstraintProvider() {
        // Public no-arg constructor required by OptaPlanner
    }

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
                // HARD CONSTRAINTS - MUSZĄ być spełnione
                allRoutesAssigned(factory),           // Priorytet 1: Wszystkie trasy MUSZĄ być przypisane
                maxOvermileage300km(factory),          // Priorytet 2: Max +300km przekroczenia
                noTimeConflicts(factory),              // Priorytet 3: Brak konfliktów czasowych
                noServiceConflicts(factory),           // Priorytet 4: Nie przypisuj podczas serwisu
                respectSwapCooldown(factory),          // Priorytet 5: 90 dni między zamianami

                // MEDIUM CONSTRAINTS - Mocno pożądane
                minimizeOvermileageCost(factory),      // Minimalizuj koszty overmileage
                avoidVehiclesNeedingService(factory),  // Unikaj pojazdów bliskich serwisu

                // SOFT CONSTRAINTS - Nice to have
                minimizeRepositioningCost(factory),    // Minimalizuj koszty przestawień
                balanceFleetUtilization(factory),      // Równomierne wykorzystanie floty
                preferVehiclesWithHighAvailability(factory) // Preferuj pojazdy z dużym zapasem km
        };
    }

    // ==================== HARD CONSTRAINTS ====================

    /**
     * NAJWAŻNIEJSZE: Wszystkie trasy MUSZĄ być przypisane do pojazdu
     * Zgodnie z zasadą: "Wszystkie trasy muszą być zrealizowane - nie można rezygnować z tras"
     */
    Constraint allRoutesAssigned(ConstraintFactory factory) {
        return factory.forEach(RouteAssignment.class)
                .filter(a -> a.getVehicle() == null)
                .penalize(HardMediumSoftScore.ONE_HARD, a -> 1000000) // Ogromna kara!
                .asConstraint("ALL_ROUTES_MUST_BE_ASSIGNED");
    }

    /**
     * Pojazd nie może przekroczyć rocznego limitu o więcej niż 300 km
     * Limit roczny = proportionalLimit + 300 km
     */
    Constraint maxOvermileage300km(ConstraintFactory factory) {
        return factory.forEach(RouteAssignment.class)
                .filter(a -> {
                    if (a.getVehicle() == null) return false;
                    Vehicle v = a.getVehicle();
                    int projected = v.getCurrentYearKm() + (int) a.getDistanceKm();
                    return projected > v.getMaxAllowedKm();
                })
                .penalize(HardMediumSoftScore.ONE_HARD, a -> {
                    Vehicle v = a.getVehicle();
                    int projected = v.getCurrentYearKm() + (int) a.getDistanceKm();
                    int overage = projected - v.getMaxAllowedKm();
                    // Bezpieczne od overflow: max penalty 100k
                    return Math.min(overage * 100, 100000);
                })
                .asConstraint("CRITICAL_MAX_300KM_OVERMILEAGE");
    }

    /**
     * Pojazd nie może być przypisany do dwóch tras równocześnie
     * Zgodnie z zasadą: "Brak podwójnych przydziałów – jeden pojazd ≠ dwie trasy równocześnie"
     */
    Constraint noTimeConflicts(ConstraintFactory factory) {
        return factory.forEachUniquePair(RouteAssignment.class,
                        Joiners.equal(RouteAssignment::getVehicle))
                .filter((a1, a2) -> {
                    if (a1.getVehicle() == null) return false;
                    // Sprawdź czy czasy się nakładają
                    return !(a1.getEndTime().isBefore(a2.getStartTime()) ||
                            a2.getEndTime().isBefore(a1.getStartTime()));
                })
                .penalize(HardMediumSoftScore.ONE_HARD, (a1, a2) -> 50000)
                .asConstraint("NO_TIME_CONFLICTS");
    }

    /**
     * Pojazd zablokowany na serwis (48h) nie może być przypisany do tras
     * Zgodnie z zasadą: "Serwis blokuje pojazd na 48h"
     */
    Constraint noServiceConflicts(ConstraintFactory factory) {
        return factory.forEach(RouteAssignment.class)
                .filter(a -> a.getVehicle() != null &&
                        a.getVehicle().getServiceBlockedUntil() != null &&
                        a.getStartTime().isBefore(a.getVehicle().getServiceBlockedUntil()))
                .penalize(HardMediumSoftScore.ONE_HARD, a -> 20000)
                .asConstraint("NO_SERVICE_CONFLICTS");
    }

    /**
     * Pojazd może być zamieniany maksymalnie raz na 90 dni (edytowalny parametr)
     * Zgodnie z zasadą: "Limit częstotliwości zamian – max 1 zamiana/pojazd/3 miesiące"
     * Parametr SWAP_COOLDOWN_DAYS może być edytowany
     */
    Constraint respectSwapCooldown(ConstraintFactory factory) {
        return factory.forEach(RouteAssignment.class)
                .filter(a -> a.getVehicle() != null && !a.getVehicle().canSwap())
                .penalize(HardMediumSoftScore.ONE_HARD, a -> 10000)
                .asConstraint("SWAP_COOLDOWN_90_DAYS");
    }

    // ==================== MEDIUM CONSTRAINTS ====================

    /**
     * Minimalizuj koszty nadprzebiegu (0.92 PLN/km)
     * Overmileage dozwolony, ale kosztowny
     */
    Constraint minimizeOvermileageCost(ConstraintFactory factory) {
        return factory.forEach(RouteAssignment.class)
                .filter(a -> {
                    if (a.getVehicle() == null) return false;
                    Vehicle v = a.getVehicle();
                    int projected = v.getCurrentYearKm() + (int) a.getDistanceKm();
                    return projected > v.getProportionalLimit();
                })
                .penalize(HardMediumSoftScore.ONE_MEDIUM, a -> {
                    Vehicle v = a.getVehicle();
                    int projected = v.getCurrentYearKm() + (int) a.getDistanceKm();
                    int overage = Math.min(projected - v.getProportionalLimit(), Constants.MAX_OVERMILEAGE_KM);
                    // 0.92 PLN/km * 100 dla skali
                    return (int) (overage * Constants.OVERMILEAGE_COST_PER_KM * 100);
                })
                .asConstraint("MINIMIZE_OVERMILEAGE_COST");
    }

    /**
     * Unikaj przypisywania tras do pojazdów wymagających serwisu
     * Serwis w widełkach ±1000 km od interwału
     * Zgodnie z zasadą: "Serwisy mogą być wykonywane w widełkach 1000+- km"
     */
    Constraint avoidVehiclesNeedingService(ConstraintFactory factory) {
        return factory.forEach(RouteAssignment.class)
                .filter(a -> a.getVehicle() != null && a.getVehicle().needsService())
                .penalize(HardMediumSoftScore.ONE_MEDIUM, a -> {
                    Vehicle v = a.getVehicle();
                    // Większa kara jeśli już przekroczono interwał (critical)
                    return v.criticalService() ? 10000 : 5000;
                })
                .asConstraint("AVOID_SERVICE_NEEDED");
    }

    // ==================== SOFT CONSTRAINTS ====================

    /**
     * Minimalizuj koszty przestawień pojazdów
     * Koszt: 1000 PLN + 1 PLN/km + 150 PLN/h (z locations_relations)
     * Zgodnie z zasadą: "Koszt zamiany pojazdu wyliczamy z tabeli locations_relations"
     */
    Constraint minimizeRepositioningCost(ConstraintFactory factory) {
        return factory.forEach(RouteAssignment.class)
                .filter(a -> a.getVehicle() != null)
                .penalize(HardMediumSoftScore.ONE_SOFT, a -> {
                    // Koszt repozycjonowania - obliczany w post-processingu
                    // Tu dajemy symboliczną karę za każde przestawienie
                    if (a.getVehicle().getCurrentLocationId() != a.getStartLocationId()) {
                        return 1000; // Base penalty
                    }
                    return 0;
                })
                .asConstraint("MINIMIZE_REPOSITIONING_WITHOUT_TRAILER");
    }

    /**
     * Równoważenie wykorzystania floty
     * Unikaj pojazdów powyżej 98% i poniżej 70% wykorzystania limitu
     */
    Constraint balanceFleetUtilization(ConstraintFactory factory) {
        return factory.forEach(RouteAssignment.class)
                .filter(a -> a.getVehicle() != null)
                .penalize(HardMediumSoftScore.ONE_SOFT, a -> {
                    Vehicle v = a.getVehicle();
                    int newKm = v.getCurrentYearKm() + (int) a.getDistanceKm();
                    int limit = v.getProportionalLimit();

                    if (limit == 0) return 0; // Unikaj dzielenia przez 0

                    double util = (double) newKm / limit * 100;

                    if (util > 98) {
                        // Zbyt wysokie wykorzystanie - kara
                        return (int) ((util - 98) * 1000);
                    }
                    if (util < 70) {
                        // Zbyt niskie wykorzystanie - mniejsza kara
                        return (int) ((70 - util) * 500);
                    }
                    return 0;
                })
                .asConstraint("BALANCE_UTILIZATION");
    }

    /**
     * Preferuj pojazdy z większą dostępnością km
     * Im więcej km zostało, tym lepiej
     */
    Constraint preferVehiclesWithHighAvailability(ConstraintFactory factory) {
        return factory.forEach(RouteAssignment.class)
                .filter(a -> a.getVehicle() != null)
                .reward(HardMediumSoftScore.ONE_SOFT, a -> {
                    Vehicle v = a.getVehicle();
                    int availableKm = v.getAvailableKm();
                    // Reward proporcjonalny do dostępnych km (max 10k)
                    return Math.min(availableKm / 10, 10000);
                })
                .asConstraint("PREFER_HIGH_AVAILABILITY");
    }
}