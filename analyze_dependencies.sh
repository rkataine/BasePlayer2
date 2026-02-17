#!/bin/bash
# Analyze import dependencies to find tightly coupled classes

echo "=== Classes with most dependencies (complexity hotspots) ==="
echo ""
for file in src/main/java/org/baseplayer/**/*.java; do
    count=$(grep -c "^import org.baseplayer" "$file" 2>/dev/null || echo 0)
    basename=$(basename "$file")
    printf "%3d imports: %s\n" "$count" "$basename"
done | sort -rn | head -20

echo ""
echo "=== Most imported classes (high coupling risk) ==="
echo ""
for file in src/main/java/org/baseplayer/**/*.java; do
    classname=$(basename "$file" .java)
    count=$(grep -r "import org.baseplayer.*\.$classname" src/main/java --include="*.java" | wc -l)
    if [ "$count" -gt 5 ]; then
        printf "%3d usages: %s\n" "$count" "$classname"
    fi
done | sort -rn

echo ""
echo "=== Static access / God objects (SharedModel, etc.) ==="
grep -r "SharedModel\." src/main/java --include="*.java" -l | wc -l | xargs echo "Files accessing SharedModel:"
grep -r "\.get()\." src/main/java --include="*.java" | grep -v "//.*\.get()" | wc -l | xargs echo "Singleton access patterns:"
