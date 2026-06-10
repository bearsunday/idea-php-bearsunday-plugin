<?php

declare(strict_types=1);

use RecursiveDirectoryIterator;
use RecursiveIteratorIterator;
use SplFileInfo;

$root = dirname(__DIR__);
$tmpDir = $root . '/var/tmp';

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
    $ok = $file->isDir() ? rmdir($path) : unlink($path);
    if (! $ok) {
        fwrite(STDERR, sprintf("Failed to remove %s\n", $path));
        exit(1);
    }
}
