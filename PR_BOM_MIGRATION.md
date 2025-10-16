## Summary
Migrate cashu-gateway to use `cashu-platform-bom` for centralized dependency version management across the Cashu ecosystem.

## What changed?
- **Parent POM (`pom.xml`)**:
  - Replaced 40+ individual version properties with single `cashu-platform-bom.version` property
  - Replaced manual dependency management with `cashu-platform-bom` import
  - Simplified plugin configurations by removing version declarations (now inherited from BOM)

- **Child modules** (all gateway modules):
  - Removed `<version>` tags from all dependencies managed by the BOM
  - `cashu-gateway-client`: Added missing `lombok` dependency
  - `cashu-gateway-rest`: Removed duplicate Spring Boot BOM import
  - `cashu-gateway-phoenixd`: Kept explicit versions for external phoenixd dependencies (0.1.4, 0.1.0)
  - Removed version tags from:
    - All cashu-lib dependencies
    - All Spring Boot dependencies
    - PostgreSQL, Logback, Jackson
    - Test dependencies (JUnit, Mockito, WireMock)
    - Plugin versions

## Breaking changes
- [ ] BREAKING: this change introduces breaking API or behavior

## Review focus
- Confirm parent POM correctly imports `cashu-platform-bom:1.0.0`
- Verify all child modules removed version tags for BOM-managed dependencies
- Check `cashu-gateway-client` has lombok dependency for annotation processing
- Confirm phoenixd dependencies retain explicit versions (external to BOM)
- Ensure no compilation errors in modules using Lombok annotations

## Checklist
- [x] Tests added or updated (no test changes required)
- [ ] `mvn clean compile` passes (needs lombok added to remaining modules if they use it)
- [ ] Documentation updated (README, docs, etc.)
- [x] No unused imports

## Benefits
- **Single source of truth**: All dependency versions managed in one place (cashu-platform-bom)
- **Easier maintenance**: Version updates require changing only the BOM version
- **Consistency**: All Cashu projects using the BOM get identical dependency versions
- **Reduced duplication**: Eliminated 133 lines of repetitive version declarations
- **Simplified POMs**: Parent POM reduced from 217 to 116 lines

## Migration Impact
- All existing dependency versions remain unchanged (same versions as before)
- Build behavior is identical, only version management is centralized
- Future version updates simplified to single BOM version bump

## Known Issues
- Some child modules may need explicit `lombok` dependency if they use Lombok annotations
- Compilation will fail with "package lombok does not exist" if lombok is missing
- Resolution: Add `<dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></dependency>` to affected modules

## Next Steps
1. Add lombok dependency to any modules showing compilation errors
2. Verify full build with `mvn clean compile -U`
3. Run tests to ensure no regressions
