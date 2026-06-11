<?php

declare(strict_types=1);

$root = dirname(__DIR__);
$tmpDir = $root . '/var/tmp';
$protectedDirs = [
    $tmpDir . '/cache',
    $tmpDir . '/di',
];

if (! is_dir($tmpDir)) {
    exit(0);
}

$iterator = new RecursiveIteratorIterator(
    new RecursiveDirectoryIterator($tmpDir, RecursiveDirectoryIterator::SKIP_DOTS),
    RecursiveIteratorIterator::CHILD_FIRST,
);

/** @var SplFileInfo $file */
foreach ($iterator as $file) {
    $path = $file->getPathname();
    if ($file->getBasename() === '.gitkeep' || in_array($path, $protectedDirs, true)) {
        continue;
    }

    $ok = $file->isDir() ? rmdir($path) : unlink($path);
    if (! $ok) {
        fwrite(STDERR, sprintf("Failed to remove %s\n", $path));
        exit(1);
    }
}
