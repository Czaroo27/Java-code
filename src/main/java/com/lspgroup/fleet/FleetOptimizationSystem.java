package com.lspgroup.fleet;

import com.mongodb.client.*;
import lombok.Data;
import org.bson.Document;
import org.optaplanner.core.api.domain.entity.PlanningEntity;
import org.optaplanner.core.api.domain.lookup.PlanningId;
import org.optaplanner.core.api.domain.solution.*;
import org.optaplanner.core.api.domain.valuerange.ValueRangeProvider;
import org.optaplanner.core.api.domain.variable.PlanningVariable;
import org.optaplanner.core.api.score.buildin.hardmediumsoft.HardMediumSoftScore;
import org.optaplanner.core.api.score.stream.*;
import org.optaplanner.core.api.solver.*;
import org.optaplanner.core.config.solver.SolverConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

// ==================== CONSTANTS ====================
class Constants {
    static final int MAX_OVERMILEAGE_KM = 300;
    static final double OVERMILEAGE_COST_PER_KM = 0.92;
    static final double SWAP_BASE_COST = 1000.0;
    static final double SWAP_COST_PER_KM = 1.0;
    static final double SWAP_COST_PER_HOUR = 150.0;
    static final double SPEED_WITHOUT_TRAILER_KMH = 80.0;
    static final int SWAP_COOLDOWN_DAYS = 90;
    static final int SERVICE_BLOCK_HOURS = 48;
    static final int SERVICE_TOLERANCE_KM = 1000;
    static final int HIGH_MILEAGE_THRESHOLD = 200000;
}

// ==================== DOMAIN MODEL ====================

@Data
class Vehicle {
    private int id;
    private String registration;
    private String brand;
    private int currentOdometerKm;
    private int currentLocationId;
    private int annualLimitKm;
    private LocalDateTime leasingStartDate;
    private int currentYearKm;
    private LocalDateTime lastSwapDate;
    private LocalDateTime serviceBlockedUntil;
    private int lastServiceKm;

    public boolean isLongTermLease() {
        return annualLimitKm > Constants.HIGH_MILEAGE_THRESHOLD;
    }

    public int getProportionalLimit() {
        if (isLongTermLease()) {
            if (leasingStartDate == null) return annualLimitKm;
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime leaseEnd = leasingStartDate.plusYears(3);
            long totalMonths = ChronoUnit.MONTHS.between(leasingStartDate, leaseEnd);
            long elapsedMonths = ChronoUnit.MONTHS.between(leasingStartDate, now);
            if (elapsedMonths <= 0) return 0;
            if (elapsedMonths >= totalMonths) return annualLimitKm;
            return (int)((double)annualLimitKm / totalMonths * elapsedMonths);
        }

        if (leasingStartDate == null) return annualLimitKm;
        LocalDateTime now = LocalDateTime.now();
        long months = ChronoUnit.MONTHS.between(leasingStartDate, now);
        if (months >= 12) {
            long monthsInPeriod = months % 12;
            return monthsInPeriod == 0 ? annualLimitKm : (int)((double)annualLimitKm / 12 * monthsInPeriod);
        }
        return (int)((double)annualLimitKm / 12 * months);
    }

    public int getMaxAllowedKm() {
        return getProportionalLimit() + Constants.MAX_OVERMILEAGE_KM;
    }

    public int getAvailableKm() {
        return Math.max(0, getMaxAllowedKm() - currentYearKm);
    }

    public boolean canSwap() {
        if (lastSwapDate != null) {
            long days = ChronoUnit.DAYS.between(lastSwapDate, LocalDateTime.now());
            if (days < Constants.SWAP_COOLDOWN_DAYS) return false;
        }
        return serviceBlockedUntil == null || LocalDateTime.now().isAfter(serviceBlockedUntil);
    }

    public int getServiceInterval() {
        return switch (brand) {
            case "DAF", "Scania" -> 120000;
            case "Volvo" -> 110000;
            default -> 110000;
        };
    }

    public boolean needsService() {
        int kmSinceService = currentOdometerKm - lastServiceKm;
        int interval = getServiceInterval();
        return kmSinceService >= (interval - Constants.SERVICE_TOLERANCE_KM);
    }

    public boolean criticalService() {
        int kmSinceService = currentOdometerKm - lastServiceKm;
        int interval = getServiceInterval();
        return kmSinceService >= interval;
    }
}

@Data
@PlanningEntity
class RouteAssignment {
    @PlanningId
    private int routeId;
    private int startLocationId;
    private int endLocationId;
    private double distanceKm;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    @PlanningVariable(valueRangeProviderRefs = "vehicleRange", nullable = true)
    private Vehicle vehicle;

    public RouteAssignment() {}

    public RouteAssignment(int routeId, int startLoc, int endLoc, double dist,
                           LocalDateTime start, LocalDateTime end) {
        this.routeId = routeId;
        this.startLocationId = startLoc;
        this.endLocationId = endLoc;
        this.distanceKm = dist;
        this.startTime = start;
        this.endTime = end;
    }

    public double calculateRepositioningCost(Map<String, LocationDistance> distances) {
        if (vehicle == null) {
            return 0;
        }

        if (vehicle.getCurrentLocationId() == -1 ||
                vehicle.getCurrentLocationId() == startLocationId) {
            return 0;
        }

        String key = vehicle.getCurrentLocationId() + "-" + startLocationId;
        LocationDistance dist = distances.get(key);

        if (dist == null) {
            dist = new LocationDistance(300.0, 5.0);
        }

        double timeHours = dist.distanceKm / Constants.SPEED_WITHOUT_TRAILER_KMH;

        return Constants.SWAP_BASE_COST +
                (dist.distanceKm * Constants.SWAP_COST_PER_KM) +
                (timeHours * Constants.SWAP_COST_PER_HOUR);
    }
}

@Data
class LocationDistance {
    double distanceKm;
    double timeHours;

    public LocationDistance(double distanceKm, double timeHours) {
        this.distanceKm = distanceKm;
        this.timeHours = timeHours;
    }
}

@PlanningSolution
@Data
class FleetSolution {
    @ValueRangeProvider(id = "vehicleRange")
    private List<Vehicle> vehicles;

    @PlanningEntityCollectionProperty
    private List<RouteAssignment> assignments;

    @PlanningScore
    private HardMediumSoftScore score;

    private Map<String, LocationDistance> distances;

    public FleetSolution() {}

    public FleetSolution(List<Vehicle> vehicles, List<RouteAssignment> assignments,
                         Map<String, LocationDistance> distances) {
        this.vehicles = vehicles;
        this.assignments = assignments;
        this.distances = distances;
    }
}

// ==================== MONGODB SERVICE ====================

class MongoService {
    private final MongoClient mongoClient;
    private final MongoDatabase database;

    public MongoService(String uri, String dbName) {
        this.mongoClient = MongoClients.create(uri);
        this.database = mongoClient.getDatabase(dbName);
    }

    private int safeParseInt(Object obj, int defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Integer) return (Integer) obj;

        String str = obj.toString().trim();
        if (str.isEmpty() || str.equalsIgnoreCase("N/A") || str.equalsIgnoreCase("null")) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private double safeParseDouble(Object obj, double defaultValue) {
        if (obj == null) return defaultValue;
        if (obj instanceof Number) return ((Number) obj).doubleValue();

        String str = obj.toString().trim();
        if (str.isEmpty() || str.equalsIgnoreCase("N/A") || str.equalsIgnoreCase("null")) {
            return defaultValue;
        }

        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public List<Vehicle> loadVehicles() {
        List<Vehicle> vehicles = new ArrayList<>();
        MongoCollection<Document> collection = database.getCollection("vehicles");

        for (Document doc : collection.find()) {
            try {
                Vehicle v = new Vehicle();

                v.setId(safeParseInt(doc.get("Id"), 0));
                v.setRegistration(doc.getString("registration_number"));
                v.setBrand(doc.getString("brand"));
                v.setCurrentOdometerKm(safeParseInt(doc.get("current_odometer_km"), 0));
                v.setCurrentLocationId(-1);
                v.setAnnualLimitKm(safeParseInt(doc.get("leasing_limit_km"), 150000));
                v.setLastServiceKm(safeParseInt(doc.get("Leasing_start_km"), 0));

                String leasingStart = doc.getString("leasing_start_date");
                if (leasingStart != null && !leasingStart.isEmpty() && !leasingStart.equals("N/A")) {
                    try {
                        v.setLeasingStartDate(LocalDateTime.parse(leasingStart.replace(" ", "T")));
                    } catch (Exception e) {
                        // Ignoruj błędne daty
                    }
                }

                v.setServiceBlockedUntil(null);
                v.setLastSwapDate(null);
                v.setCurrentYearKm(0);

                vehicles.add(v);
            } catch (Exception e) {
                System.out.println("⚠️ Error loading vehicle: " + e.getMessage());
            }
        }

        System.out.println("✅ Loaded " + vehicles.size() + " vehicles");
        return vehicles;
    }

    public List<RouteAssignment> loadRoutes(String startDate, int days) {
        List<RouteAssignment> routes = new ArrayList<>();
        MongoCollection<Document> routesColl = database.getCollection("routes");
        MongoCollection<Document> segmentsColl = database.getCollection("segments");

        LocalDateTime start = LocalDateTime.parse(startDate + "T00:00:00");
        LocalDateTime end = start.plusDays(days);

        System.out.println("🔍 Loading routes from " + start + " to " + end);

        for (Document doc : routesColl.find()) {
            try {
                String startStr = doc.getString("start_datetime");
                if (startStr == null || startStr.equals("N/A")) continue;

                LocalDateTime routeStart = LocalDateTime.parse(startStr.replace(" ", "T"));
                if (routeStart.isBefore(start) || routeStart.isAfter(end)) continue;

                int routeId = safeParseInt(doc.get("id"), -1);
                if (routeId == -1) continue;

                List<Document> segments = new ArrayList<>();
                segmentsColl.find(new Document("route_id", routeId))
                        .sort(new Document("seq", 1))
                        .into(segments);

                if (segments.isEmpty()) continue;

                int startLoc = safeParseInt(segments.get(0).get("start_loc_id"), 1);
                int endLoc = safeParseInt(segments.get(segments.size() - 1).get("end_loc_id"), 1);

                double totalDistance = 0.0;
                for (Document seg : segments) {
                    totalDistance += safeParseDouble(seg.get("distance_travelled_km"), 0.0);
                }

                String endStr = doc.getString("end_datetime");
                LocalDateTime routeEnd = (endStr != null && !endStr.equals("N/A")) ?
                        LocalDateTime.parse(endStr.replace(" ", "T")) : routeStart.plusHours(8);

                routes.add(new RouteAssignment(routeId, startLoc, endLoc, totalDistance, routeStart, routeEnd));
            } catch (Exception e) {
                System.out.println("⚠️ Error loading route: " + e.getMessage());
            }
        }

        System.out.println("✅ Loaded " + routes.size() + " routes");
        return routes;
    }

    public Map<String, LocationDistance> loadLocationDistances() {
        Map<String, LocationDistance> map = new HashMap<>();
        MongoCollection<Document> collection = database.getCollection("locations_relations");

        for (Document doc : collection.find()) {
            int loc1 = safeParseInt(doc.get("id_loc_1"), 0);
            int loc2 = safeParseInt(doc.get("id_loc_2"), 0);
            double dist = safeParseDouble(doc.get("dist"), 300.0);
            double time = safeParseDouble(doc.get("time"), 5.0) / 60.0;

            LocationDistance ld = new LocationDistance(dist, time);
            map.put(loc1 + "-" + loc2, ld);
            map.put(loc2 + "-" + loc1, ld);
        }

        System.out.println("✅ Loaded " + map.size() + " location distances");
        return map;
    }
}

// ==================== OPTIMIZATION SERVICE ====================

class OptimizationService {
    private final MongoService mongoService;
    private final SolverManager<FleetSolution, Long> solverManager;

    public OptimizationService(MongoService mongoService) {
        this.mongoService = mongoService;

        SolverConfig config = new SolverConfig()
                .withSolutionClass(FleetSolution.class)
                .withEntityClasses(RouteAssignment.class)
                .withConstraintProviderClass(FleetConstraintProvider.class)
                .withTerminationSpentLimit(Duration.ofSeconds(300));

        this.solverManager = SolverManager.create(config);
    }

    public OptimizationResult optimize(String startDate, int days, int timeoutSec) {
        long startTime = System.currentTimeMillis();

        List<Vehicle> vehicles = mongoService.loadVehicles();
        List<RouteAssignment> routes = mongoService.loadRoutes(startDate, days);
        Map<String, LocationDistance> distances = mongoService.loadLocationDistances();

        if (vehicles.isEmpty() || routes.isEmpty()) {
            return new OptimizationResult("ERROR", "No data found", null);
        }

        System.out.println("📊 Problem size: " + vehicles.size() + " vehicles, " + routes.size() + " routes");

        FleetSolution problem = new FleetSolution(vehicles, routes, distances);

        FleetSolution solution;
        try {
            SolverJob<FleetSolution, Long> job = solverManager.solve(1L, problem);
            solution = job.getFinalBestSolution();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new OptimizationResult("ERROR", "Solver interrupted", null);
        } catch (Exception e) {
            e.printStackTrace();
            return new OptimizationResult("ERROR", "Solver error: " + e.getMessage(), null);
        }

        double computationTime = (System.currentTimeMillis() - startTime) / 1000.0;

        CostBreakdown costs = calculateCosts(solution);

        return new OptimizationResult(
                solution.getScore().isFeasible() ? "OPTIMAL" : "INFEASIBLE",
                solution.getScore().toString(),
                solution,
                costs,
                computationTime);
    }

    private CostBreakdown calculateCosts(FleetSolution solution) {
        CostBreakdown costs = new CostBreakdown();

        Map<Integer, Double> vehicleKm = new HashMap<>();
        Map<Integer, List<RouteAssignment>> vehicleAssignments = new HashMap<>();

        for (RouteAssignment a : solution.getAssignments()) {
            if (a.getVehicle() == null) continue;
            vehicleAssignments.computeIfAbsent(a.getVehicle().getId(), k -> new ArrayList<>()).add(a);
        }

        for (List<RouteAssignment> assignments : vehicleAssignments.values()) {
            assignments.sort((a, b) -> a.getStartTime().compareTo(b.getStartTime()));
        }

        for (Map.Entry<Integer, List<RouteAssignment>> entry : vehicleAssignments.entrySet()) {
            Vehicle v = null;
            List<RouteAssignment> assignments = entry.getValue();

            for (int i = 0; i < assignments.size(); i++) {
                RouteAssignment current = assignments.get(i);
                if (v == null) v = current.getVehicle();

                int prevEndLocation;

                if (i == 0) {
                    prevEndLocation = current.getStartLocationId();

                    System.out.println("🚀 Vehicle " + v.getRegistration() +
                            " STARTS at location " + current.getStartLocationId() +
                            " (first assignment) | Cost: 0 PLN");
                } else {
                    prevEndLocation = assignments.get(i - 1).getEndLocationId();
                }

                if (prevEndLocation != current.getStartLocationId()) {
                    double repoCost = current.calculateRepositioningCost(solution.getDistances());
                    costs.totalRepositioningCost += repoCost;

                    if (repoCost > 0) {
                        costs.repositioningDetails.add(new RepositioningDetail(
                                current.getRouteId(),
                                v.getId(),
                                v.getRegistration(),
                                prevEndLocation,
                                current.getStartLocationId(),
                                repoCost));

                        System.out.println("🚚 Vehicle " + v.getRegistration() +
                                " REPOSITIONING: " + prevEndLocation + " → " +
                                current.getStartLocationId() + " | Cost: " +
                                String.format("%.2f PLN", repoCost));
                    }
                } else {
                    if (i > 0) {
                        RouteAssignment prev = assignments.get(i - 1);
                        long waitDays = ChronoUnit.DAYS.between(prev.getEndTime(), current.getStartTime());

                        System.out.println("💤 Vehicle " + v.getRegistration() +
                                " WAITING at location " + prevEndLocation +
                                " for " + waitDays + " days | Cost: 0 PLN");
                    }
                }

                vehicleKm.merge(v.getId(), current.getDistanceKm(), Double::sum);
            }
        }

        for (Vehicle v : solution.getVehicles()) {
            double routeKm = vehicleKm.getOrDefault(v.getId(), 0.0);
            int totalKm = v.getCurrentYearKm() + (int) routeKm;
            int limit = v.getProportionalLimit();
            int maxAllowed = v.getMaxAllowedKm();

            if (totalKm > limit) {
                int overage = Math.min(totalKm - limit, Constants.MAX_OVERMILEAGE_KM);
                double cost = overage * Constants.OVERMILEAGE_COST_PER_KM;
                costs.totalOvermileageCost += cost;

                costs.overmileageDetails.add(new OvermileageDetail(
                        v.getId(),
                        v.getRegistration(),
                        totalKm,
                        limit,
                        maxAllowed,
                        overage,
                        cost,
                        totalKm > maxAllowed ? "CRITICAL" : "WARNING"));
            }
        }

        costs.totalCost = costs.totalRepositioningCost + costs.totalOvermileageCost;

        return costs;
    }
}

// ==================== RESULT DTOs ====================

@Data
class OptimizationResult {
    private String status;
    private String score;
    private FleetSolution solution;
    private CostBreakdown costs;
    private double computationTimeSeconds;

    public OptimizationResult(String status, String score, FleetSolution solution) {
        this.status = status;
        this.score = score;
        this.solution = solution;
    }

    public OptimizationResult(String status, String score, FleetSolution solution,
                              CostBreakdown costs, double computationTime) {
        this.status = status;
        this.score = score;
        this.solution = solution;
        this.costs = costs;
        this.computationTimeSeconds = computationTime;
    }
}

@Data
class CostBreakdown {
    double totalCost = 0;
    double totalRepositioningCost = 0;
    double totalOvermileageCost = 0;
    List<RepositioningDetail> repositioningDetails = new ArrayList<>();
    List<OvermileageDetail> overmileageDetails = new ArrayList<>();
}

@Data
class RepositioningDetail {
    int routeId;
    int vehicleId;
    String vehicleRegistration;
    int fromLocationId;
    int toLocationId;
    double cost;

    public RepositioningDetail(int routeId, int vehicleId, String vehicleRegistration,
                               int fromLocationId, int toLocationId, double cost) {
        this.routeId = routeId;
        this.vehicleId = vehicleId;
        this.vehicleRegistration = vehicleRegistration;
        this.fromLocationId = fromLocationId;
        this.toLocationId = toLocationId;
        this.cost = cost;
    }
}

@Data
class OvermileageDetail {
    int vehicleId;
    String vehicleRegistration;
    int totalKm;
    int annualLimit;
    int maxAllowed;
    int overageKm;
    double cost;
    String severity;

    public OvermileageDetail(int vehicleId, String vehicleRegistration, int totalKm,
                             int annualLimit, int maxAllowed, int overageKm,
                             double cost, String severity) {
        this.vehicleId = vehicleId;
        this.vehicleRegistration = vehicleRegistration;
        this.totalKm = totalKm;
        this.annualLimit = annualLimit;
        this.maxAllowed = maxAllowed;
        this.overageKm = overageKm;
        this.cost = cost;
        this.severity = severity;
    }
}

// ==================== REST CONTROLLER ====================

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
class FleetController {

    private final OptimizationService optimizationService;

    public FleetController(@Value("${mongodb.uri}") String mongoUri,
                           @Value("${mongodb.database}") String mongoDb) {
        MongoService mongoService = new MongoService(mongoUri, mongoDb);
        this.optimizationService = new OptimizationService(mongoService);
    }

    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
                "service", "LSP Fleet Optimization",
                "version", "2.0.0",
                "solver", "OptaPlanner AI",
                "features", List.of(
                        "✅ All routes MUST be assigned (priority #1)",
                        "✅ MAX 300km overmileage enforced",
                        "✅ Repositioning cost optimization",
                        "✅ Service intervals with ±1000km tolerance",
                        "✅ Flexible swap cooldown (editable parameter)",
                        "✅ Long-term lease support (>200k km)"));
    }

    @PostMapping("/optimize")
    public Map<String, Object> optimize(@RequestBody Map<String, Object> request) {
        String startDate = (String) request.getOrDefault("start_date", "2024-01-01");
        int days = (int) request.getOrDefault("days", 30);
        int timeout = (int) request.getOrDefault("timeout", 120);

        OptimizationResult result = optimizationService.optimize(startDate, days, timeout);

        Map<String, Object> response = new HashMap<>();
        response.put("status", result.getStatus());
        response.put("score", result.getScore());
        response.put("computation_time_sec", result.getComputationTimeSeconds());

        if (result.getCosts() != null) {
            CostBreakdown costs = result.getCosts();

            response.put("costs", Map.of(
                    "total_cost_PLN", String.format("%.2f", costs.totalCost),
                    "repositioning_cost_PLN", String.format("%.2f", costs.totalRepositioningCost),
                    "overmileage_cost_PLN", String.format("%.2f", costs.totalOvermileageCost)));

            response.put("repositioning_details", costs.repositioningDetails.stream()
                    .map(d -> Map.of(
                            "route_id", d.routeId,
                            "vehicle", d.vehicleRegistration,
                            "from_location", d.fromLocationId,
                            "to_location", d.toLocationId,
                            "cost_PLN", String.format("%.2f", d.cost)))
                    .collect(Collectors.toList()));

            response.put("overmileage_details", costs.overmileageDetails.stream()
                    .map(d -> Map.of(
                            "vehicle", d.vehicleRegistration,
                            "total_km", d.totalKm,
                            "annual_limit_km", d.annualLimit,
                            "max_allowed_km", d.maxAllowed,
                            "overage_km", d.overageKm,
                            "cost_PLN", String.format("%.2f", d.cost),
                            "severity", d.severity))
                    .collect(Collectors.toList()));
        }

        if (result.getSolution() != null) {
            long assigned = result.getSolution().getAssignments().stream()
                    .filter(a -> a.getVehicle() != null)
                    .count();

            response.put("assignments", Map.of(
                    "total_routes", result.getSolution().getAssignments().size(),
                    "assigned_routes", assigned,
                    "unassigned_routes", result.getSolution().getAssignments().size() - assigned));

            // NOWE: Dodaj szczegóły przypisań tras
            List<Map<String, Object>> assignmentDetails = result.getSolution().getAssignments().stream()
                    .filter(a -> a.getVehicle() != null)
                    .sorted((a, b) -> a.getStartTime().compareTo(b.getStartTime()))
                    .map(a -> {
                        Map<String, Object> detail = new HashMap<>();
                        detail.put("route_id", a.getRouteId());
                        detail.put("vehicle_id", a.getVehicle().getId());
                        detail.put("vehicle_registration", a.getVehicle().getRegistration());
                        detail.put("start_location", a.getStartLocationId());
                        detail.put("end_location", a.getEndLocationId());
                        detail.put("distance_km", String.format("%.2f", a.getDistanceKm()));
                        detail.put("start_time", a.getStartTime().toString());
                        detail.put("end_time", a.getEndTime().toString());
                        return detail;
                    })
                    .collect(Collectors.toList());

            response.put("route_assignments", assignmentDetails);
        }

        return response;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return Map.of(
                "max_overmileage_km", Constants.MAX_OVERMILEAGE_KM,
                "overmileage_cost_per_km_PLN", Constants.OVERMILEAGE_COST_PER_KM,
                "service_tolerance_km", Constants.SERVICE_TOLERANCE_KM,
                "repositioning", Map.of(
                        "base_cost_PLN", Constants.SWAP_BASE_COST,
                        "cost_per_km_PLN", Constants.SWAP_COST_PER_KM,
                        "cost_per_hour_PLN", Constants.SWAP_COST_PER_HOUR,
                        "speed_without_trailer_kmh", Constants.SPEED_WITHOUT_TRAILER_KMH),
                "constraints", Map.of(
                        "swap_cooldown_days", Constants.SWAP_COOLDOWN_DAYS,
                        "service_block_hours", Constants.SERVICE_BLOCK_HOURS,
                        "high_mileage_threshold", Constants.HIGH_MILEAGE_THRESHOLD));
    }
}

// ==================== MAIN APPLICATION ====================

@SpringBootApplication(exclude = {
        org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration.class,
        org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration.class
})
public class FleetOptimizationSystem {
    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║   🚛 LSP FLEET OPTIMIZATION v2.0 - OptaPlanner  ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("📋 Key Features:");
        System.out.println("  ✅ ALL routes MUST be assigned (hard constraint #1)");
        System.out.println("  ✅ currentYearKm = 0 on 2024-01-01 (annual reset)");
        System.out.println("  ✅ Service tolerance: ±1000 km");
        System.out.println("  ✅ Long-term leases: >200k km = total lease limit");
        System.out.println("  ✅ Flexible swap cooldown (editable parameter)");
        System.out.println();
        System.out.println("💰 Cost Structure:");
        System.out.println("  • Repositioning: 1000 PLN + 1 PLN/km + 150 PLN/h");
        System.out.println("  • Overmileage: 0.92 PLN/km (max 300km/vehicle/year)");
        System.out.println();
        System.out.println("🌐 API Endpoints:");
        System.out.println("  POST /api/optimize - Run optimization");
        System.out.println("  GET  /api/summary  - Configuration parameters");
        System.out.println();
        System.out.println("Starting server...");
        System.out.println("═══════════════════════════════════════════════════");

        SpringApplication.run(FleetOptimizationSystem.class, args);
    }
}