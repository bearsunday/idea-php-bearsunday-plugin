<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Query;

use MyVendor\MyProject\Input\PointInput;
use Ray\MediaQuery\Annotation\DbQuery;

interface PointQueryInterface
{
    /** @return array{x: int, y: int, squaredDistance: int} */
    #[DbQuery('point_distance', type: 'row')]
    public function distance(int $x, int $y): array;

    /** @return array{x: int, y: int, squaredDistance: int} */
    #[DbQuery('point_distance', type: 'row')]
    public function distanceByInput(PointInput $point): array;
}
