# Refactoring Quick Start Guide

This guide helps you begin refactoring BasePlayer2 using the documented architecture plan.

## 📊 Current State (Baseline)

**As of February 17, 2026:**
- **Refactoring Progress:** 0/25 goals complete (0%)
- **Critical Issues:** 8 files over 800 lines
- **SharedModel Usage:** 13 files, 151 static accesses
- **Total Code:** 74 files, 21,835 lines

## 🚀 Getting Started

### 1. Review Documentation

Read in this order:
1. **ARCHITECTURE.md** - Complete refactoring plan
2. **This file** - Quick start instructions
3. Run analysis scripts to understand current state

### 2. Run Analysis Scripts

```bash
# See what needs to be refactored
./code_quality.sh

# Track progress against goals
./refactoring_progress.sh

# Re-run after each phase to track improvements
```

### 3. Set Up Your Workflow

**Before starting each refactoring session:**
```bash
# Create a feature branch
git checkout -b refactor/phase-1-services

# Run tests (if they exist)
./gradlew test

# Run the app to ensure it works
./gradlew run
```

**After each logical change:**
```bash
# Build and verify
./gradlew clean build

# Test manually (checklist below)
# Commit with descriptive message
git add .
git commit -m "refactor: create ViewportState service"

# Track progress
./refactoring_progress.sh | tail -20
```

## 📋 Phase 1: Eliminate SharedModel (START HERE)

**Goal:** Replace global state with dependency-injected services  
**Time:** 1-2 days  
**Complexity:** Medium

### Step 1.1: Create ViewportState Service

**Create:** `src/main/java/org/baseplayer/services/ViewportState.java`

```java
package org.baseplayer.services;

import javafx.beans.property.*;
import org.baseplayer.draw.DrawStack;

/**
 * Manages viewport state (current chromosome, active view regions).
 * Replaces SharedModel viewport fields.
 */
public class ViewportState {
    private final StringProperty currentChromosome = new SimpleStringProperty("1");
    private final ObservableList<DrawStack> activeStacks = FXCollections.observableArrayList();
    
    public String getCurrentChromosome() {
        return currentChromosome.get();
    }
    
    public void setCurrentChromosome(String chromosome) {
        this.currentChromosome.set(chromosome);
    }
    
    public StringProperty currentChromosomeProperty() {
        return currentChromosome;
    }
    
    public ObservableList<DrawStack> getActiveStacks() {
        return activeStacks;
    }
    
    // Add other viewport state as needed
}
```

**Verify:**
```bash
./gradlew compileJava
# Check: Should compile successfully
```

### Step 1.2: Create SampleRegistry Service

**Create:** `src/main/java/org/baseplayer/services/SampleRegistry.java`

```java
package org.baseplayer.services;

import javafx.beans.property.*;
import javafx.collections.*;
import org.baseplayer.sample.SampleTrack;

/**
 * Manages sample tracks and visibility.
 * Replaces SharedModel sample fields.
 */
public class SampleRegistry {
    private final ObservableList<SampleTrack> sampleTracks = FXCollections.observableArrayList();
    private final IntegerProperty hoverSample = new SimpleIntegerProperty(-1);
    
    private int firstVisibleSample = 0;
    private int lastVisibleSample = 0;
    private double scrollBarPosition = 0;
    private double sampleHeight = 0;
    
    public ObservableList<SampleTrack> getSampleTracks() {
        return sampleTracks;
    }
    
    public void addSampleTrack(SampleTrack track) {
        sampleTracks.add(track);
    }
    
    public IntegerProperty hoverSampleProperty() {
        return hoverSample;
    }
    
    public int getFirstVisibleSample() {
        return firstVisibleSample;
    }
    
    public void setFirstVisibleSample(int index) {
        this.firstVisibleSample = index;
    }
    
    public int getLastVisibleSample() {
        return lastVisibleSample;
    }
    
    public void setLastVisibleSample(int index) {
        this.lastVisibleSample = index;
    }
    
    public int getVisibleSampleCount() {
        return lastVisibleSample - firstVisibleSample + 1;
    }
    
    // Add getters/setters for other fields
}
```

### Step 1.3: Create ReferenceGenomeService

**Create:** `src/main/java/org/baseplayer/services/ReferenceGenomeService.java`

```java
package org.baseplayer.services;

import org.baseplayer.io.ReferenceGenome;

/**
 * Manages reference genome access.
 * Replaces SharedModel.referenceGenome.
 */
public class ReferenceGenomeService {
    private ReferenceGenome currentGenome;
    
    public ReferenceGenome getCurrentGenome() {
        return currentGenome;
    }
    
    public void setCurrentGenome(ReferenceGenome genome) {
        this.currentGenome = genome;
    }
    
    public String getSequence(String chrom, int start, int end) {
        if (currentGenome == null) {
            throw new IllegalStateException("No reference genome loaded");
        }
        return currentGenome.getSequence(chrom, start, end);
    }
}
```

**Verify:**
```bash
./gradlew compileJava
./refactoring_progress.sh | head -15
# Check: Should show 3 services created
```

### Step 1.4: Create ServiceRegistry (Dependency Injection)

**Create:** `src/main/java/org/baseplayer/services/ServiceRegistry.java`

```java
package org.baseplayer.services;

/**
 * Simple service registry for dependency injection.
 * Singleton that provides access to all services.
 */
public class ServiceRegistry {
    private static ServiceRegistry instance;
    
    private final ViewportState viewportState;
    private final SampleRegistry sampleRegistry;
    private final ReferenceGenomeService referenceGenomeService;
    
    private ServiceRegistry() {
        this.viewportState = new ViewportState();
        this.sampleRegistry = new SampleRegistry();
        this.referenceGenomeService = new ReferenceGenomeService();
    }
    
    public static ServiceRegistry getInstance() {
        if (instance == null) {
            instance = new ServiceRegistry();
        }
        return instance;
    }
    
    public ViewportState getViewportState() {
        return viewportState;
    }
    
    public SampleRegistry getSampleRegistry() {
        return sampleRegistry;
    }
    
    public ReferenceGenomeService getReferenceGenomeService() {
        return referenceGenomeService;
    }
}
```

### Step 1.5: Migrate One Controller (MainController)

**Example migration:**

**Before:**
```java
// In MainController.java
import org.baseplayer.SharedModel;

public class MainController {
    public void initialize() {
        String chrom = SharedModel.currentChromosome;
        SharedModel.sampleTracks.add(newTrack);
    }
}
```

**After:**
```java
// In MainController.java
import org.baseplayer.services.*;

public class MainController {
    private final ViewportState viewportState;
    private final SampleRegistry sampleRegistry;
    
    public MainController() {
        ServiceRegistry services = ServiceRegistry.getInstance();
        this.viewportState = services.getViewportState();
        this.sampleRegistry = services.getSampleRegistry();
    }
    
    public void initialize() {
        String chrom = viewportState.getCurrentChromosome();
        sampleRegistry.getSampleTracks().add(newTrack);
    }
}
```

**Strategy:**
- Migrate ONE file at a time
- Test after each file
- Keep SharedModel until all migrations complete

### Step 1.6: Create SharedModel Adapter (Temporary)

During migration, keep old code working:

```java
// Update SharedModel.java to delegate to new services
public class SharedModel {
    @Deprecated
    public static String getCurrentChromosome() {
        return ServiceRegistry.getInstance().getViewportState().getCurrentChromosome();
    }
    
    @Deprecated
    public static List<SampleTrack> getSampleTracks() {
        return ServiceRegistry.getInstance().getSampleRegistry().getSampleTracks();
    }
    
    // Delegate all methods...
}
```

### Step 1.7: Migrate All 13 Files

**Files to migrate:**
1. MainController.java
2. MenuBarController.java
3. DrawStack.java
4. DrawChromData.java
5. DrawSampleData.java
6. DrawFunctions.java
7. DrawIndicators.java
8. CoverageDrawer.java
9. TrackInfo.java
10. FeatureTracksSidebar.java
11. SampleDataManager.java
12. BAMFileReader.java
13. CRAMFileReader.java

**For each file:**
```bash
# 1. Open file
# 2. Add service fields
# 3. Replace SharedModel.X with service.getX()
# 4. Build
./gradlew compileJava

# 5. Test manually
./gradlew run

# 6. Commit
git add .
git commit -m "refactor: migrate [FileName] to use services"
```

### Step 1.8: Delete SharedModel

**After all files migrated:**
```bash
# Verify no usages remain
grep -r "SharedModel\." src/main/java/org/baseplayer --include="*.java"
# Should return nothing

# Delete SharedModel.java
git rm src/main/java/org/baseplayer/SharedModel.java

# Build and test
./gradlew clean build
./gradlew run

# Commit
git add .
git commit -m "refactor: remove SharedModel god object"

# Check progress
./refactoring_progress.sh | head -20
# Should show Phase 1 complete!
```

## ✅ Manual Testing Checklist

After each migration, test these features:

- [ ] Application launches without errors
- [ ] Open BAM/CRAM file
- [ ] Navigate viewport (pan, zoom)
- [ ] Switch chromosomes
- [ ] View gene information
- [ ] Load track data
- [ ] Sample hover effects work
- [ ] No console errors

## 🔄 Iterative Process

After completing Phase 1:

```bash
# Run analysis again
./code_quality.sh
./refactoring_progress.sh

# Should see:
# - SharedModel usages: 0
# - Phase 1: 100% complete
# - Ready for Phase 2

# Merge to main
git checkout main
git merge refactor/phase-1-services

# Start Phase 2
git checkout -b refactor/phase-2-controllers
```

## 📚 Reference Commands

```bash
# Build project
./gradlew clean build

# Run application
./gradlew run

# Find all usages of a class
grep -r "ClassName" src/main/java --include="*.java"

# Count lines in a file
wc -l src/main/java/org/baseplayer/SomeFile.java

# Find large files
find src/main/java -name "*.java" -exec wc -l {} + | sort -rn | head -20

# Track progress
./refactoring_progress.sh

# Check code quality
./code_quality.sh
```

## 🎯 Success Metrics for Phase 1

After completing Phase 1, you should see:

```bash
./refactoring_progress.sh
```

**Expected output:**
```
═══ Phase 1: Eliminate SharedModel (Week 1) ═══

✓ Create ViewportState.java
✓ Create SampleRegistry.java
✓ Create ReferenceGenomeService.java
✓ SharedModel import statements: 0 / 0
✓ SharedModel static accesses: 0 / 0
```

## 🆘 Troubleshooting

**Build fails after creating services:**
- Check package names match directory structure
- Ensure all imports are correct
- Run `./gradlew clean` and rebuild

**Application doesn't work after migration:**
- Check ServiceRegistry is initialized in MainApp
- Verify all SharedModel references are replaced
- Use git diff to see what changed

**Not sure what to migrate next:**
- Run `grep -r "SharedModel\." src --include="*.java" | wc -l`
- Pick the file with most usages
- Start simple (controllers first)

## 📞 Questions?

If stuck:
1. Review ARCHITECTURE.md for context
2. Check git history: `git log --oneline`
3. Run analysis scripts for current state
4. Start small - one file at a time

## 🎉 Next Steps

Once Phase 1 complete:
- **Phase 2:** Split MenuBarController and MainController
- **Phase 3:** Refactor SampleFile and file readers
- **Phase 4:** Organize drawing layer

See ARCHITECTURE.md for full roadmap.