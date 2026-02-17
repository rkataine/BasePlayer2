# BasePlayer2 Architecture Refactoring Plan

**Generated:** February 17, 2026  
**Purpose:** Document current architecture problems and provide concrete refactoring roadmap

---

## Table of Contents
1. [Current Architecture Overview](#current-architecture-overview)
2. [Identified Problems](#identified-problems)
3. [Proposed Architecture](#proposed-architecture)
4. [Refactoring Roadmap](#refactoring-roadmap)
5. [Package Structure](#package-structure)
6. [Migration Strategy](#migration-strategy)

---

## Current Architecture Overview

### Package Organization (Current)
```
org.baseplayer/
├── controllers/          # UI controllers (3 files)
├── draw/                 # Rendering logic (14 files, mixed concerns)
├── reads/bam/           # BAM/CRAM file access (8 files)
├── io/                   # I/O operations (10 files)
├── tracks/              # Feature tracks (14 files)
├── annotation/          # Gene/COSMIC data (7 files)
├── sample/              # Sample data models (2 files)
├── gene/                # Gene models (3 files)
├── variant/             # Variant models (1 file)
├── utils/               # Utilities (6 files)
├── SharedModel.java     # ⚠️ GLOBAL STATE
└── MainApp.java         # Application entry
```

### Current Component Interactions
```
┌─────────────────┐
│   Controllers   │──────────────┐
│  (MenuBar,      │              │
│   Main,         │              ▼
│   Sidebar)      │         SharedModel (God Object)
└─────────────────┘              │
        │                        │
        │ Direct                 │ Static
        │ Access                 │ Access
        ▼                        ▼
┌─────────────────┐         ┌──────────────┐
│   Draw Layer    │────────▶│   I/O Layer  │
│  (13 classes)   │         │ (Readers,    │
│                 │         │  API clients)│
└─────────────────┘         └──────────────┘
        │                        │
        │                        │
        ▼                        ▼
┌──────────────────────────────────┐
│        Data Models               │
│  (Sample, Gene, BAMRecord)       │
└──────────────────────────────────┘
```

---

## Identified Problems

### 🔴 Critical Issues

#### 1. **SharedModel God Object**
**Location:** `SharedModel.java`  
**Problem:** 13 classes access global mutable state
- All fields are `public static`
- No encapsulation or thread safety
- Makes testing impossible
- Creates hidden dependencies

**Files Affected:**
- BAMFileReader.java
- CRAMFileReader.java
- FeatureTracksSidebar.java
- MenuBarController.java
- MainController.java
- SampleDataManager.java
- DrawFunctions.java
- DrawChromData.java
- DrawSampleData.java
- DrawStack.java
- DrawIndicators.java
- CoverageDrawer.java
- TrackInfo.java

#### 2. **God Controllers (Violation of SRP)**
**MenuBarController.java (730 lines)**
- Handles navigation, file loading, view management, settings
- 50+ methods with mixed concerns
- Directly accesses I/O layer, bypassing any service layer

**MainController.java (501 lines)**
- Application initialization + UI management + event handling
- Should be split into separate concerns

#### 3. **Monolithic Classes (>1000 lines)**
**CRAMFileReader.java (1314 lines)**
- File parsing + decompression + reference handling + caching
- Should be split into: Reader, Decoder, ReferenceResolver, Cache

**SampleFile.java (1073 lines)**
- File management + read fetching + coverage calculation + caching + packing
- Too many responsibilities

**BAMFileReader.java (918 lines)**
- Similar issues to CRAMFileReader

#### 4. **Drawing Layer Complexity**
**Problem:** Business logic mixed with rendering
- `DrawChromData.java` (881 lines) - Reference rendering + data fetching
- `DrawSampleData.java` (834 lines) - Sample rendering + data access
- `CoverageDrawer.java` (759 lines) - Complex rendering + layout logic

#### 5. **Tight Coupling**
**High-coupling classes (>10 internal dependencies):**
- DrawChromData (12 internal imports)
- MenuBarController (10 internal imports)
- GeneInfoPopup (10 internal imports)

**Most-depended-upon classes:**
- Sample (16 usages)
- DrawStack (6 usages)
- MainController (6 usages)

#### 6. **No Service Layer**
Controllers directly instantiate and call:
- File readers
- API clients
- Drawing components
- Data managers

This creates tight coupling and makes testing difficult.

---

## Proposed Architecture

### Target Architecture Pattern: **Layered + Service-Oriented**

```
┌─────────────────────────────────────────────────────────────┐
│                     PRESENTATION LAYER                      │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │ Controllers  │  │   Renderers  │  │   UI Components │  │
│  │ (thin logic) │  │   (canvas)   │  │     (popups)    │  │
│  └──────────────┘  └──────────────┘  └─────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                      SERVICE LAYER                          │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │  Navigation │  │   Sample     │  │   Annotation     │  │
│  │   Service   │  │   Service    │  │    Service       │  │
│  ├─────────────┤  ├──────────────┤  ├──────────────────┤  │
│  │   Track     │  │   Variant    │  │   Reference      │  │
│  │  Service    │  │   Service    │  │   Service        │  │
│  └─────────────┘  └──────────────┘  └──────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    REPOSITORY LAYER                         │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │    BAM      │  │     VCF      │  │   Annotation     │  │
│  │ Repository  │  │  Repository  │  │   Repository     │  │
│  ├─────────────┤  ├──────────────┤  ├──────────────────┤  │
│  │    API      │  │   Reference  │  │     Cache        │  │
│  │ Repository  │  │  Repository  │  │   Repository     │  │
│  └─────────────┘  └──────────────┘  └──────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                      DATA LAYER                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  File Readers │ API Clients │ Cache Managers │ Models│  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### Dependency Injection Strategy

**Replace:**
```java
// Current (static access)
SharedModel.sampleTracks
FetchManager.get()
Settings.get()
```

**With:**
```java
// Proposed (constructor injection)
public class MenuBarController {
    private final SampleRegistry sampleRegistry;
    private final NavigationService navigationService;
    private final FileService fileService;
    
    public MenuBarController(SampleRegistry registry, 
                            NavigationService nav,
                            FileService files) {
        this.sampleRegistry = registry;
        this.navigationService = nav;
        this.fileService = files;
    }
}
```

---

## Refactoring Roadmap

### Phase 1: Foundation (Week 1) - **ELIMINATE SharedModel**

#### 1.1 Create Core Services
**Create:** `org.baseplayer.services/`

**New Classes:**
```java
// ViewportState.java - Replaces SharedModel viewport fields
public class ViewportState {
    private String currentChromosome = "1";
    private final ObservableList<DrawStack> activeStacks;
    // Methods: getCurrentChromosome(), setCurrentChromosome(), etc.
}

// SampleRegistry.java - Replaces SharedModel sample management
public class SampleRegistry {
    private final ObservableList<SampleTrack> sampleTracks;
    private final IntegerProperty hoverSample;
    private int firstVisibleSample = 0;
    private int lastVisibleSample = 0;
    // Methods: getSampleTracks(), addSample(), getVisibleSamples(), etc.
}

// ReferenceGenomeService.java - Replaces SharedModel.referenceGenome
public class ReferenceGenomeService {
    private ReferenceGenome currentGenome;
    // Methods: loadGenome(), getCurrentGenome(), getSequence(), etc.
}
```

**Effort:** 4-6 hours  
**Lines added:** ~300  
**Lines modified:** ~200 (13 files)

#### 1.2 Replace SharedModel References
**Strategy:** Incremental replacement per package

1. Controllers: Inject services via constructor
2. Draw classes: Pass services as parameters
3. Readers: Accept services in constructor

**Test after each package migration**

---

### Phase 2: Split God Controllers (Week 2)

#### 2.1 Extract MenuBarController Commands

**Split into:**
```
org.baseplayer.controllers.commands/
├── NavigationCommands.java      # Jump, zoom, pan operations
├── FileCommands.java             # Open BAM/VCF/BED, save sessions
├── ViewCommands.java             # Track visibility, UI settings
└── SearchCommands.java           # Gene search, position search
```

**MenuBarController** becomes thin coordinator (< 200 lines)

**Effort:** 6-8 hours  
**Result:** 730 lines → 5 files of ~150 lines each

#### 2.2 Refactor MainController

**Split into:**
- `MainController.java` - UI lifecycle only (< 150 lines)
- `InitializationService.java` - App startup, data loading
- `EventCoordinator.java` - Inter-component communication

**Effort:** 4-6 hours  
**Result:** 501 lines → 3 files of ~170 lines each

---

### Phase 3: Break Up Data Access (Week 3)

#### 3.1 Split SampleFile (1073 lines)

**Refactor to:**
```
org.baseplayer.reads.bam/
├── SampleFile.java               # Core file handle (< 200 lines)
├── ReadFetcher.java              # Streaming/query logic
├── ReadCache.java                # Caching strategy
├── CoverageCalculator.java       # Coverage computation
└── ReadPacker.java               # Row packing algorithm
```

**Effort:** 8-10 hours  
**Complexity:** High (critical path, extensive testing needed)

#### 3.2 Split File Readers

**CRAMFileReader (1314 lines) → 4 classes:**
- `CRAMFileReader.java` - Core reader (< 300 lines)
- `CRAMDecoder.java` - CRAM format decoding
- `CRAMReferenceResolver.java` - Reference handling
- `CRAMCache.java` - Block caching

**BAMFileReader (918 lines) → 3 classes:**
- `BAMFileReader.java` - Core reader (< 300 lines)
- `BAMDecoder.java` - BAM format decoding  
- `BAMCache.java` - Block caching

**Effort:** 10-12 hours total

#### 3.3 Refactor API Clients

**Pattern:** Extract response parsing from HTTP logic

```
org.baseplayer.io.api/
├── UcscApiClient.java            # HTTP client (< 300 lines)
├── UcscResponseParser.java       # Parse responses
├── AlphaFoldApiClient.java       # HTTP client (< 300 lines)
└── AlphaFoldResponseParser.java  # Parse responses
```

**Effort:** 4-6 hours

---

### Phase 4: Organize Draw Layer (Week 4)

#### 4.1 Separate Rendering from Data Access

**Current issue:** Renderers fetch and render data

**Target pattern:** Renderers receive pre-fetched data

```java
// Before:
public void drawSamples() {
    List<BAMRecord> reads = sampleFile.fetchReads(...); // BAD
    for (BAMRecord read : reads) {
        drawRead(read);
    }
}

// After:
public void drawSamples(List<BAMRecord> reads) {
    for (BAMRecord read : reads) {
        drawRead(read);
    }
}
// Data fetching moved to service layer
```

**Affected classes:**
- DrawChromData.java
- DrawSampleData.java
- CoverageDrawer.java

**Effort:** 8-10 hours

#### 4.2 Extract Layout Logic

**Create:** `org.baseplayer.draw.layout/`
```java
public class TrackLayoutManager {
    public TrackLayout calculateLayout(ViewportState viewport, 
                                      List<Track> tracks);
}
```

Removes layout calculations from individual renderers.

**Effort:** 6-8 hours

---

### Phase 5: Testing & Documentation (Week 5)

#### 5.1 Add Unit Tests
- Test all new service classes
- Test extracted command classes
- Integration tests for critical paths

**Target coverage:** 60% for new code

#### 5.2 Update Documentation
- Architecture diagram (auto-generated from code)
- Package-level README files
- Contributing guide with architecture overview

---

## Package Structure

### Proposed Final Structure

```
org.baseplayer/
├── MainApp.java
├── services/                      # NEW - Business logic
│   ├── NavigationService.java
│   ├── SampleRegistry.java
│   ├── ViewportState.java
│   ├── ReferenceGenomeService.java
│   ├── AnnotationService.java
│   ├── TrackService.java
│   └── VariantService.java
│
├── controllers/                   # Thin UI controllers
│   ├── MainController.java       # Refactored (< 200 lines)
│   ├── MenuBarController.java    # Refactored (< 200 lines)
│   ├── SidebarController.java
│   └── commands/                  # NEW - Command pattern
│       ├── NavigationCommands.java
│       ├── FileCommands.java
│       ├── ViewCommands.java
│       └── SearchCommands.java
│
├── draw/                          # Pure rendering
│   ├── renderers/                 # NEW - organized renderers
│   │   ├── ChromosomeRenderer.java
│   │   ├── SampleRenderer.java
│   │   ├── CoverageRenderer.java
│   │   └── GeneRenderer.java
│   ├── layout/                    # NEW - layout logic
│   │   └── TrackLayoutManager.java
│   ├── popups/                    # NEW - UI popups
│   │   ├── GeneInfoPopup.java
│   │   ├── ReadInfoPopup.java
│   │   └── AminoAcidPopup.java
│   └── DrawStack.java            # Viewport container
│
├── repositories/                  # NEW - Data access layer
│   ├── BAMRepository.java
│   ├── VCFRepository.java
│   ├── AnnotationRepository.java
│   ├── ReferenceRepository.java
│   └── ApiRepository.java
│
├── reads/
│   └── bam/
│       ├── SampleFile.java       # Refactored (< 300 lines)
│       ├── ReadFetcher.java      # NEW
│       ├── ReadCache.java        # NEW
│       ├── CoverageCalculator.java # NEW
│       ├── ReadPacker.java       # NEW
│       ├── BAMFileReader.java    # Refactored
│       ├── BAMDecoder.java       # NEW
│       ├── CRAMFileReader.java   # Refactored
│       ├── CRAMDecoder.java      # NEW
│       ├── FetchManager.java
│       └── (other existing files)
│
├── io/
│   ├── api/                       # NEW - Organized API clients
│   │   ├── UcscApiClient.java
│   │   ├── UcscResponseParser.java
│   │   ├── AlphaFoldApiClient.java
│   │   ├── AlphaFoldResponseParser.java
│   │   └── GnomadApiClient.java
│   ├── ReferenceGenome.java
│   ├── Settings.java
│   ├── UserPreferences.java
│   └── (other existing files)
│
├── tracks/                        # Feature tracks
├── annotation/                    # Gene/COSMIC data
├── sample/                        # Sample models
├── gene/                          # Gene models
├── variant/                       # Variant models
└── utils/                         # Utilities
```

---

## Migration Strategy

### Incremental Migration (Recommended)

**Key Principle:** Never break the build

#### Step-by-Step Approach:

1. **Create new classes alongside old ones**
   - Don't delete old code immediately
   - Mark old classes as `@Deprecated`

2. **Migrate one package at a time**
   - Start with least-coupled packages
   - Test thoroughly after each migration

3. **Use adapter pattern during transition**
   ```java
   // Temporary adapter during migration
   public class SharedModelAdapter {
       private final SampleRegistry registry;
       private final ViewportState viewport;
       
       // Delegate old calls to new services
       public static List<SampleTrack> getSampleTracks() {
           return getInstance().registry.getSampleTracks();
       }
   }
   ```

4. **Delete old code only after full migration**
   - Ensure all tests pass
   - Remove `@Deprecated` classes

### Refactoring Tools

**Use automated refactoring where possible:**
- IntelliJ IDEA: Extract Method, Extract Class, Move Members
- Find/Replace for simple renames
- Git commits per logical change (easy rollback)

### Testing During Migration

**Test at each step:**
```bash
# After each refactoring:
./gradlew clean build
./gradlew test  # (once tests exist)

# Manual testing checklist:
# ✓ Open BAM file
# ✓ Navigate viewport
# ✓ Zoom in/out
# ✓ Load tracks
# ✓ View gene info
```

---

## Success Metrics

### Code Quality Goals

**Target metrics after refactoring:**
- ✅ No class > 500 lines
- ✅ No method > 100 lines
- ✅ No God objects (classes with >15 dependencies)
- ✅ 60% test coverage for new code
- ✅ All classes follow Single Responsibility Principle

### Maintainability Improvements

**Before:**
- 13 files tightly coupled to SharedModel
- Controllers with 50+ methods
- Testing: Nearly impossible

**After:**
- Dependency injection throughout
- Controllers < 200 lines, focused responsibility
- Testing: Each service independently testable

---

## Risk Mitigation

### High-Risk Areas

1. **SampleFile refactoring** - Critical path, complex caching
   - Strategy: Comprehensive manual testing
   - Fallback: Keep old implementation temporarily

2. **File reader splitting** - Low-level binary parsing
   - Strategy: Byte-level unit tests before/after
   - Validation: Compare output on same files

3. **Controller refactoring** - User-facing functionality
   - Strategy: Feature flags for gradual rollout
   - Testing: Full manual test pass per controller

### Rollback Plan

- Each refactoring phase in separate Git branch
- Merge only after validation
- Tag stable versions before risky changes

---

## Next Steps

1. **Review this document** - Team alignment on approach
2. **Set up tracking** - Use provided scripts to monitor progress  
3. **Start Phase 1** - Create ViewportState and SampleRegistry
4. **Continuous validation** - Test after each change
5. **Iterate** - Adjust plan based on learnings

---

## Notes

- **Document generated from codebase analysis on Feb 17, 2026**
- **See `refactoring_progress.sh` for automated tracking**
- **Update this doc as architecture evolves**
