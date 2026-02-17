# Refactoring Documentation - BasePlayer2

**Created:** February 17, 2026  
**Status:** Planning complete, ready to begin implementation

## 📖 Documentation Overview

This package contains a complete refactoring plan for BasePlayer2 to improve code organization, maintainability, and scalability.

### Documents Created

1. **[ARCHITECTURE.md](ARCHITECTURE.md)** - Complete architectural analysis and refactoring plan
   - Current architecture problems
   - Proposed target architecture
   - 4-phase refactoring roadmap
   - Package structure design
   - Migration strategy

2. **[REFACTORING_GUIDE.md](REFACTORING_GUIDE.md)** - Step-by-step implementation guide
   - How to start refactoring
   - Code examples for each step
   - Testing checklist
   - Troubleshooting tips

3. **Analysis Scripts:**
   - `refactoring_progress.sh` - Tracks progress against 25 refactoring goals
   - `code_quality.sh` - Analyzes code metrics and identifies hotspots

## 🎯 Current State (Baseline)

### Key Metrics
- **Total Files:** 74 Java files
- **Total Lines:** 21,835 lines of code
- **Critical Issues:** 8 files over 800 lines
- **Refactoring Progress:** 0/25 goals complete

### Top Problems Identified

1. **SharedModel God Object** - 13 files depend on global state (151 static accesses)
2. **Monolithic Classes** - CRAMFileReader (1314 lines), SampleFile (1073 lines), BAMFileReader (918 lines)
3. **God Controllers** - MenuBarController (730 lines), MainController (501 lines)
4. **High Coupling** - DrawChromData (12 internal dependencies)
5. **No Service Layer** - Controllers directly access data layer

## 🚀 Quick Start

### 1. Review the Plan

```bash
# Read the architecture document
less ARCHITECTURE.md

# Read the implementation guide
less REFACTORING_GUIDE.md
```

### 2. Run Analysis Scripts

```bash
# Make scripts executable (if not already)
chmod +x refactoring_progress.sh code_quality.sh

# See current code quality
./code_quality.sh

# Track refactoring progress
./refactoring_progress.sh
```

### 3. Start Refactoring

**Recommended first step:** Phase 1 - Eliminate SharedModel

```bash
# Create feature branch
git checkout -b refactor/phase-1-services

# Create services package
mkdir -p src/main/java/org/baseplayer/services

# Follow REFACTORING_GUIDE.md step-by-step
```

## 📋 Refactoring Phases

### Phase 1: Foundation (Week 1) - **START HERE**
**Goal:** Eliminate SharedModel god object  
**Effort:** 4-6 hours  
**Impact:** Breaks 13 tight couplings

**Tasks:**
- ✓ Create ViewportState service
- ✓ Create SampleRegistry service  
- ✓ Create ReferenceGenomeService
- ✓ Migrate 13 files to use services
- ✓ Delete SharedModel

### Phase 2: Split Controllers (Week 2)
**Goal:** Break up MenuBarController and MainController  
**Effort:** 10-14 hours  
**Impact:** Reduces controller complexity by 60%

**Tasks:**
- Extract command classes from MenuBarController
- Split MainController responsibilities
- Introduce proper controller/service separation

### Phase 3: Data Access (Week 3)
**Goal:** Break up monolithic file readers  
**Effort:** 18-22 hours  
**Impact:** Makes critical I/O code testable and maintainable

**Tasks:**
- Split SampleFile (1073→5 classes)
- Split CRAMFileReader (1314→4 classes)
- Split BAMFileReader (918→3 classes)
- Refactor API clients

### Phase 4: Drawing Layer (Week 4)
**Goal:** Organize rendering code  
**Effort:** 14-18 hours  
**Impact:** Separates rendering from business logic

**Tasks:**
- Extract renderer classes
- Create layout managers
- Organize popups into package

## 📊 Tracking Progress

### Automated Tracking

Run these scripts regularly to track improvements:

```bash
# After each file refactored
./refactoring_progress.sh | tail -30

# After each phase completed
./code_quality.sh | less
```

### Progress Visualization

The scripts show:
- ✓ Completed goals (green checkmarks)
- ◐ In-progress goals (yellow)
- ✗ Not started (red X)
- Current completion percentage

**Example output:**
```
═══ Phase 1: Eliminate SharedModel (Week 1) ═══

✓ Create ViewportState.java
✓ Create SampleRegistry.java
✓ Create ReferenceGenomeService.java
✓ SharedModel import statements: 0 / 0

Refactoring completion: 15/25 goals (60%)
```

## 🎯 Success Criteria

### Overall Goals
- [ ] No class over 500 lines
- [ ] No method over 100 lines
- [ ] No god objects (>15 dependencies)
- [ ] All files follow Single Responsibility Principle
- [ ] Dependency injection throughout

### Phase 1 Goals
- [ ] 0 files access SharedModel
- [ ] 3 new service classes created
- [ ] ServiceRegistry implemented
- [ ] All 13 files migrated

## 🔧 Development Workflow

### Before Each Session
```bash
# Pull latest changes
git pull origin main

# Create feature branch
git checkout -b refactor/descriptive-name

# Verify app works
./gradlew run
```

### During Refactoring
```bash
# After each logical change:
./gradlew compileJava        # Verify it compiles
./gradlew run                # Test manually
git add . && git commit -m "refactor: description"
```

### After Each Phase
```bash
# Run full test suite
./gradlew clean build

# Check progress
./refactoring_progress.sh
./code_quality.sh

# Merge to main
git checkout main
git merge refactor/phase-X
```

## 📁 Generated Files

The analysis scripts create timestamped reports:

```
refactoring_progress_YYYYMMDD_HHMMSS.txt
code_quality_YYYYMMDD_HHMMSS.txt
```

These are gitignored but useful for tracking progress over time.

## 🎓 Learning Resources

### Architecture Patterns Used
- **Service Layer Pattern** - Business logic separated from controllers
- **Repository Pattern** - Data access abstraction
- **Dependency Injection** - Loose coupling
- **Single Responsibility** - One class, one purpose
- **Command Pattern** - Extracting controller actions

### Recommended Reading
- Clean Architecture by Robert Martin
- Refactoring by Martin Fowler
- Working Effectively with Legacy Code by Michael Feathers

## 🆘 Getting Help

### If You're Stuck

1. **Check the guide:** REFACTORING_GUIDE.md has step-by-step instructions
2. **Run analysis:** `./code_quality.sh` shows what needs work
3. **Review examples:** REFACTORING_GUIDE.md contains code examples
4. **Start small:** Refactor one file at a time
5. **Use git:** Easy to rollback if something breaks

### Common Issues

**Q: Build fails after refactoring?**  
A: Run `./gradlew clean build` and check compile errors. Ensure imports are correct.

**Q: Not sure what to refactor next?**  
A: Run `./refactoring_progress.sh` to see pending goals.

**Q: How do I know if I'm improving things?**  
A: Run `./code_quality.sh` before and after refactoring. Numbers should improve.

## 📈 Expected Timeline

**Conservative estimate (part-time work):**
- Phase 1: 2-3 days
- Phase 2: 3-4 days  
- Phase 3: 5-6 days
- Phase 4: 4-5 days

**Total: 2-3 weeks**

**Aggressive estimate (full-time focused work):**
- Phase 1: 1 day
- Phase 2: 2 days
- Phase 3: 3 days
- Phase 4: 2 days

**Total: 1-1.5 weeks**

## 🎉 Benefits After Completion

### Code Quality
- All classes < 500 lines
- Clear separation of concerns
- Testable components
- No global state

### Maintainability
- Easy to find code
- Changes localized to one area
- New features easier to add
- Less risk of breaking things

### Performance
- Easier to optimize specific components
- Better memory management
- Cleaner cache invalidation

## 📝 Notes

- **Generated from codebase analysis:** February 17, 2026
- **Baseline metrics captured:** Run scripts to see current state
- **Living documents:** Update as architecture evolves
- **Version controlled:** Track changes in git

---

## Next Steps

1. ✓ Read ARCHITECTURE.md completely
2. ✓ Read REFACTORING_GUIDE.md  
3. ✓ Run `./code_quality.sh` to see baseline
4. ✓ Run `./refactoring_progress.sh` to see goals
5. **→ Start Phase 1:** Create service classes
6. Migrate files one by one
7. Track progress after each change
8. Celebrate when Phase 1 complete! 🎊

---

**Ready to begin?** Start with [REFACTORING_GUIDE.md](REFACTORING_GUIDE.md) Phase 1!
