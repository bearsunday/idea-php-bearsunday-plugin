<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Query;

use BEAR\RepositoryModule\Annotation\Query;
use Ray\Di\Di\Named;

final class QueryModuleDemo
{
    /** @Query("point_distance") */
    #[Named('point_distance')]
    public function __invoke(): void
    {
    }
}
