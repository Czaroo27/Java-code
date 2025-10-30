# LSP Fleet Optimization - OptaPlanner AI

🚛 **Backend dla optymalizacji floty ciężarowej z OptaPlanner**

## 📋 Funkcje

- ✅ **MAX 300km overmileage enforced** (hard constraint)
- ✅ **Repositioning BEZ NACZEPY** (80 km/h - szybciej i taniej!)
- ✅ **Detailed cost breakdown** (repositioning + overmileage)
- ✅ **90-day swap cooldown**
- ✅ **Service blocking** (48h)
- ✅ **Multi-objective optimization** (Tabu Search + Simulated Annealing)

---

## 🚀 Jak Uruchomić

### 1. Build Project

```bash
mvn clean package
```

### 2. Set Environment Variables

**Linux/Mac:**

```bash
export MONGODB_URI="mongodb://localhost:27017"
export MONGODB_DB="fleet_management"
```

**Windows (PowerShell):**

```powershell
$env:MONGODB_URI="mongodb://localhost:27017"
$env:MONGODB_DB="fleet_management"
```

### 3. Run

**Opcja A: Spring Boot Maven plugin**

```bash
mvn spring-boot:run
```

**Opcja B: Java JAR**

```bash
java -jar target/fleet-optimizer-1.0.0.jar
```

---

## 🌐 API Endpoints

### POST `/api/optimize` - Główna Optymalizacja

**Request:**

```bash
curl -X POST http://localhost:8080/api/optimize \
  -H "Content-Type: application/json" \
  -d '{
    "start_date": "2024-01-01",
    "days": 30,
    "timeout": 60
  }'
```

**Response:**

```json
{
  "status": "OPTIMAL",
  "score": "0hard/-125000medium/-3450000soft",
  "computation_time_sec": 58.3,

  "costs": {
    "total_cost_PLN": "15847.50",
    "repositioning_cost_PLN": "12500.00",
    "overmileage_cost_PLN": "3347.50"
  },

  "repositioning_details": [
    {
      "route_id": 1234,
      "vehicle": "ABC-1234",
      "from_location": 5,
      "to_location": 12,
      "cost_PLN": "1575.00"
    }
  ],

  "overmileage_details": [
    {
      "vehicle": "DEF-9012",
      "total_km": 150250,
      "annual_limit_km": 150000,
      "max_allowed_km": 150300,
      "overage_km": 250,
      "cost_PLN": "230.00",
      "severity": "WARNING"
    }
  ],

  "assignments": {
    "total_routes": 450,
    "assigned_routes": 448,
    "unassigned_routes": 2
  }
}
```

### GET `/api/summary` - Podsumowanie Kosztów

```bash
curl http://localhost:8080/api/summary
```

**Response:**

```json
{
  "max_overmileage_km": 300,
  "overmileage_cost_per_km_PLN": 0.92,

  "repositioning": {
    "base_cost_PLN": 1000.0,
    "cost_per_km_PLN": 1.0,
    "cost_per_hour_PLN": 150.0,
    "speed_without_trailer_kmh": 80.0,
    "note": "Faster and cheaper without trailer!"
  },

  "constraints": {
    "swap_cooldown_days": 90,
    "service_block_hours": 48
  }
}
```

---

## 💰 Przykłady Kosztów

### Repositioning BEZ NACZEPY

**Scenariusz 1: Krótki dojazd (100 km)**

- Dystans: 100 km
- Czas: 100 / 80 = 1.25 h
- **Koszt: 1000 + (100 × 1) + (1.25 × 150) = 1287.50 PLN**

**Scenariusz 2: Średni dojazd (250 km)**

- Dystans: 250 km
- Czas: 250 / 80 = 3.125 h
- **Koszt: 1000 + (250 × 1) + (3.125 × 150) = 1718.75 PLN**

**Scenariusz 3: Długi dojazd (500 km)**

- Dystans: 500 km
- Czas: 500 / 80 = 6.25 h
- **Koszt: 1000 + (500 × 1) + (6.25 × 150) = 2437.50 PLN**

### Overmileage (Nadprzebieg)

**Przykład 1: Lekkie przekroczenie**

- Roczny limit: 150,000 km
- Faktyczny przebieg: 150,150 km
- Nadprzebieg: 150 km
- **Koszt: 150 × 0.92 = 138.00 PLN** ⚠️ WARNING

**Przykład 2: Maksymalne przekroczenie**

- Roczny limit: 150,000 km
- Faktyczny przebieg: 150,300 km
- Nadprzebieg: 300 km (MAX!)
- **Koszt: 300 × 0.92 = 276.00 PLN** ⚠️ WARNING

**❌ NIEMOŻLIWE:**

- Roczny limit: 150,000 km
- Faktyczny przebieg: 150,350 km
- Nadprzebieg: 350 km
- **Status: INFEASIBLE** - przekroczono MAX 300km!

---

## 📊 Interpretacja Score

```
Score: -5hard/-125000medium/-3450000soft
        ↑      ↑              ↑
        |      |              └─ Koszty repositioning (minimalizowane)
        |      └────────────────── Koszty overmileage (minimalizowane)
        └───────────────────────── Naruszenia hard constraints (MUSI = 0)
```

- **Feasible Solution:** `0hard/*medium/*soft` - wszystkie hard constraints spełnione
- **Optimal Solution:** `0hard/0medium/*soft` - feasible + minimalne koszty

---

## ⚙️ Konfiguracja

### Zmiana Parametrów Kosztowych

W klasie `Constants` (`FleetOptimizationSystem.java`):

```java
static final int MAX_OVERMILEAGE_KM = 300;           // MAX nadwyżka
static final double OVERMILEAGE_COST_PER_KM = 0.92;  // PLN/km
static final double SWAP_BASE_COST = 1000.0;         // PLN base
static final double SWAP_COST_PER_KM = 1.0;          // PLN/km
static final double SWAP_COST_PER_HOUR = 150.0;      // PLN/h
static final double SPEED_WITHOUT_TRAILER_KMH = 80.0; // km/h
static final int SWAP_COOLDOWN_DAYS = 90;            // dni
```

### Zmiana Timeoutu Optymalizacji

W `OptimizationService`:

```java
.withTerminationSpentLimit(Duration.ofSeconds(120)); // 120s zamiast 60s
```

---

## 🐛 Troubleshooting

### Problem: "INFEASIBLE" w response

**Przyczyna:** Niemożliwe spełnienie wszystkich constraints  
**Rozwiązanie:**

- Sprawdź czy nie za dużo pojazdów w serwisie
- Sprawdź czy nie za wiele tras na jeden dzień
- Zwiększ timeout: `"timeout": 120`

### Problem: Wysokie koszty repositioning

**Rozwiązanie:**

- Sprawdź czy pojazdy mają poprawne `current_location_id`
- Sprawdź czy `location_relations` ma wszystkie dystanse
- System automatycznie optymalizuje - to może być najlepsze rozwiązanie

### Problem: Pojazdy przekraczają 300km

To **niemożliwe**! System ma hard constraint - nigdy nie pozwoli na > 300km.  
Jeśli widzisz `severity: "CRITICAL"` to znaczy że pojazd jest blisko limitu, ale NIE przekroczył MAX 300km.

---

## 📈 Metryki Wydajności

Typowe czasy optymalizacji:

- 100 tras, 50 pojazdów: ~15 sekund
- 300 tras, 100 pojazdów: ~40 sekund
- 500 tras, 200 pojazdów: ~60 sekund

Jakość rozwiązań:

- **OPTIMAL:** najlepsze możliwe rozwiązanie
- **FEASIBLE:** dobre rozwiązanie (może nie najlepsze)

---

## 🔍 Szczegóły Repositioning

### Dlaczego BEZ NACZEPY jest lepsze?

| Parametr        | Z NACZEPĄ   | BEZ NACZEPY | Różnica       |
| --------------- | ----------- | ----------- | ------------- |
| Prędkość        | 70 km/h     | 80 km/h     | +14% szybciej |
| Czas (250km)    | 3.57 h      | 3.125 h     | -0.445 h      |
| Koszt czasu     | 535.50 PLN  | 468.75 PLN  | -66.75 PLN    |
| Koszt całkowity | 1785.50 PLN | 1718.75 PLN | -66.75 PLN    |

**Na 10 dojazdów = 667.50 PLN oszczędności!**

---

## 📦 Struktura Projektu

```
Java-code/
├── pom.xml
├── README.md
├── src/
│   └── main/
│       ├── java/
│       │   └── com/
│       │       └── lspgroup/
│       │           └── fleet/
│       │               └── FleetOptimizationSystem.java
│       └── resources/
│           └── application.properties
└── tools/
    └── apache-maven-3.9.6/
```

---

## 📄 License

Proprietary - LSP Group

---

## 👨‍💻 Autor

LSP Fleet Optimization Team
