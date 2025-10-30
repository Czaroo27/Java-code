# Quick start script for Fleet Optimization System
# Sets environment variables and runs the Spring Boot app

# Check if user provided MongoDB URI via environment variables
if (-not $env:MONGODB_URI) {
    $env:MONGODB_URI = "mongodb://localhost:27017"
    Write-Host "⚠️  Using default MongoDB URI: $env:MONGODB_URI" -ForegroundColor Yellow
}

if (-not $env:MONGODB_DB) {
    $env:MONGODB_DB = "fleet_management"
    Write-Host "⚠️  Using default Database: $env:MONGODB_DB" -ForegroundColor Yellow
}

# Add Maven to PATH for this session
$env:PATH = 'C:\Users\CezarySzczepaniak\Desktop\Java-code\tools\apache-maven-3.9.6\bin;' + $env:PATH

Write-Host "╔═══════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║   🚛 LSP FLEET OPTIMIZATION - OptaPlanner AI    ║" -ForegroundColor Cyan
Write-Host "╚═══════════════════════════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""
Write-Host "🌐 Starting server on http://localhost:8080" -ForegroundColor Green
Write-Host "📋 MongoDB URI: $env:MONGODB_URI" -ForegroundColor Yellow
Write-Host "📂 Database: $env:MONGODB_DB" -ForegroundColor Yellow
Write-Host ""
Write-Host "Launching Spring Boot..." -ForegroundColor White
Write-Host "═══════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

# Run with Maven Spring Boot plugin (pass env vars as system properties)
# Using Maven property format to avoid PowerShell interpretation issues
mvn -f "c:\Users\CezarySzczepaniak\Desktop\Java-code\pom.xml" spring-boot:run `
    "-Dspring-boot.run.jvmArguments=-Dmongodb.uri=$env:MONGODB_URI -Dmongodb.database=$env:MONGODB_DB"
