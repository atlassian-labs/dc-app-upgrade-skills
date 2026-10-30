import semver from 'semver';

const reactPackages = new Set(['react', 'react-dom', '@types/react', '@types/react-dom']);
const dependencySections = ['dependencies', 'devDependencies', 'peerDependencies', 'optionalDependencies'];

export function assessReactTarget(packageJson, targetVersion, installedVersions = {}) {
    const target = semver.valid(targetVersion);

    if (!target || semver.major(target) !== 19) {
        throw new Error('Resolve an exact React 19 target version before assessment.');
    }

    const declarations = dependencySections.flatMap((section) =>
        Object.entries(packageJson[section] || {})
            .filter(([name]) => reactPackages.has(name))
            .map(([name, specifier]) => ({
                section,
                name,
                specifier,
                status: declarationStatus(name, specifier, target),
            })));

    const installed = [...new Set(declarations.map(({ name }) => name))].map((name) => {
        const version = installedVersions[name] ?? null;

        return {
            name,
            version,
            status: !semver.valid(version) ? 'unverified'
                : versionMatchesTarget(name, version, target) ? 'compatible' : 'needs-migration',
        };
    });

    const statuses = [...declarations, ...installed].map(({ status }) => status);
    const status = statuses.includes('needs-migration') ? 'needs-migration'
        : statuses.some((value) => value === 'unresolved' || value === 'unverified') ? 'unverified'
            : statuses.length ? 'compatible' : 'not-declared';

    return { targetVersion: target, status, declarations, installed };
}

function declarationStatus(name, specifier, targetVersion) {
    if (typeof specifier !== 'string' || !semver.validRange(specifier)) {
        return 'unresolved';
    }

    const compatible = name.startsWith('@types/')
        ? semver.intersects(specifier, '^19.0.0')
        : semver.satisfies(targetVersion, specifier);

    return compatible ? 'compatible' : 'needs-migration';
}

function versionMatchesTarget(name, version, targetVersion) {
    return name.startsWith('@types/') ? semver.major(version) === 19 : semver.eq(version, targetVersion);
}
