<?php

declare(strict_types=1);

if (getenv('COMPOSER_DEV_MODE') === '0') {
    exit(0);
}

passthru('composer bin all install --ansi', $code);
exit($code);
