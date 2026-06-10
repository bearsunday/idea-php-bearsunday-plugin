<?php

declare(strict_types=1);

// Aura.Router goto demo: Cmd/Ctrl+B on '/index' or '/dashboard' jumps to src/Resource/Page/*.
$map->get('index', '/index', '/index');
$map->get('dashboard', '/dashboard', '/dashboard');
