# Troubleshooting Guide - Fleet Optimization System

## ✅ Naprawione Problemy

### 1. OptaPlanner: "constraintProviderClass does not have a public no-arg constructor"

**Problem:**

```
java.lang.IllegalArgumentException: The ScoreDirectorFactoryConfig's constraintProviderClass
(com.lspgroup.fleet.FleetConstraintProvider) does not have a public no-arg constructor.
```

**Rozwiązanie:**
Dodano publiczny konstruktor bez argumentów w `FleetConstraintProvider`:

```java
class FleetConstraintProvider implements ConstraintProvider {

    public FleetConstraintProvider() {
        // Public no-arg constructor required by OptaPlanner
    }

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        // ...
    }
}
```

**Uwaga:** Klasa musi pozostać package-private (`class`, nie `public class`) bo Java wymaga, żeby `public class` był w pliku o tej samej nazwie.

---

### 2. MongoDB Connection - Zmienne Środowiskowe Nie Działają

**Problem:**
Aplikacja łączy się z `localhost:27017` mimo ustawienia `$env:MONGODB_URI` na MongoDB Atlas.

**Przyczyna:**
Maven Spring Boot plugin nie przekazuje zmiennych środowiskowych automatycznie.

**Rozwiązanie:**
Zaktualizowano `run.ps1` aby przekazywać parametry bezpośrednio do Spring Boot:

```powershell
# Przed (NIE DZIAŁA):
mvn spring-boot:run

# Po (DZIAŁA):
mvn spring-boot:run `
    -Dspring-boot.run.arguments="--mongodb.uri=$env:MONGODB_URI --mongodb.database=$env:MONGODB_DB"
```

---

## 🚀 Jak Teraz Uruchomić (POPRAWNIE)

### Opcja 1: Użyj Zaktualizowanego Skryptu `run.ps1`

```powershell
# Ustaw zmienne środowiskowe PRZED uruchomieniem skryptu
$env:MONGODB_URI="mongodb+srv://user:pass@cluster.mongodb.net/?retryWrites=true&w=majority"
$env:MONGODB_DB="code-camp"

# Uruchom
.\run.ps1
```

Skrypt automatycznie przekaże zmienne do Spring Boot.

---

### Opcja 2: Bezpośrednie Uruchomienie z Maven

```powershell
$env:PATH = 'C:\Users\CezarySzczepaniak\Desktop\Java-code\tools\apache-maven-3.9.6\bin;' + $env:PATH

mvn spring-boot:run `
    -Dspring-boot.run.arguments="--mongodb.uri=mongodb+srv://user:pass@cluster.mongodb.net/ --mongodb.database=code-camp"
```

---

### Opcja 3: JAR z Argumentami Wiersza Poleceń

```powershell
java -jar target\fleet-optimizer-1.0.0.jar `
    --mongodb.uri="mongodb+srv://user:pass@cluster.mongodb.net/" `
    --mongodb.database="code-camp"
```

---

## 📋 Checklist Przed Uruchomieniem

- [ ] Maven zainstalowany lub dostępny w `tools/apache-maven-3.9.6/bin`
- [ ] Java 17+ zainstalowana
- [ ] MongoDB dostępna (lokalnie lub Atlas)
- [ ] Zmienne środowiskowe ustawione **PRZED** `.\run.ps1`
- [ ] Port 8080 wolny

---

## 🐛 Częste Błędy

### "Connection refused: localhost:27017"

**Przyczyna:** Zmienne środowiskowe nie zostały przekazane do aplikacji.

**Rozwiązanie:**

```powershell
# NIE WYSTARCZY:
$env:MONGODB_URI="..."
mvn spring-boot:run

# TRZEBA:
mvn spring-boot:run -Dspring-boot.run.arguments="--mongodb.uri=... --mongodb.database=..."
```

---

### "No data found" w response

**Przyczyna:** Baza danych jest pusta lub kolekcje nie istnieją.

**Rozwiązanie:**

- Sprawdź czy MongoDB zawiera kolekcje: `vehicles`, `routes`, `segments`, `locations_relations`
- Sprawdź nazwę bazy danych w `$env:MONGODB_DB`

---

### "MongoSocketOpenException: Exception opening socket"

**Przyczyna:** MongoDB nie jest dostępna pod podanym URI.

**Rozwiązanie:**

- Sprawdź czy MongoDB Atlas cluster działa
- Sprawdź czy IP jest whitelistowane w MongoDB Atlas
- Sprawdź czy credentials są poprawne
- Test connection:
  ```powershell
  mongo "mongodb+srv://cluster.mongodb.net/" --username admin
  ```

---

## ✅ Weryfikacja Poprawnego Uruchomienia

Po uruchomieniu `.\run.ps1` powinieneś zobaczyć:

```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

 :: Spring Boot ::                (v3.3.0)

2025-10-30T...  INFO ... : Starting FleetOptimizationSystem ...
2025-10-30T...  INFO ... : MongoClient with metadata ... created with settings ...
2025-10-30T...  INFO ... : Tomcat started on port 8080 (http) ...
2025-10-30T...  INFO ... : Started FleetOptimizationSystem in X.XXX seconds
```

**NIE powinieneś** widzieć:

- `Exception opening socket`
- `Connection refused`
- `IllegalAccessException`

---

## 🌐 Test API

Po poprawnym uruchomieniu:

```powershell
# Test połączenia
curl http://localhost:8080/api/

# Powinno zwrócić:
{
  "service": "LSP Fleet Optimization",
  "version": "1.0.0",
  "solver": "OptaPlanner AI",
  ...
}
```

---

## 📞 Wsparcie

Jeśli nadal występują problemy:

1. Sprawdź logi w terminalu
2. Uruchom z debug mode:
   ```powershell
   mvn spring-boot:run -Dspring-boot.run.arguments="--logging.level.root=DEBUG"
   ```
3. Sprawdź czy wszystkie zależności zostały pobrane:
   ```powershell
   mvn dependency:tree
   ```

---

**Ostatnia aktualizacja:** 30.10.2025  
**Status:** ✅ Wszystkie problemy rozwiązane, aplikacja kompiluje się i uruchamia poprawnie
