<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Query;

/**
 * Demo target for Ray.QueryModule SQL goto from @Query.
 */
interface LegacyPointQueryInterface
{
    /**
     * Cmd/Ctrl-click point_distance to jump to var/db/sql/point_distance.sql.
     *
     * @Query("point_distance")
     * @return array{x: int, y: int, squaredDistance: int}
     */
    public function distance(int $x, int $y): array;
}
