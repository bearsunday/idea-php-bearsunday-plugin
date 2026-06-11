<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Resource\App;

use BEAR\Resource\Annotation\JsonSchema;
use BEAR\Resource\ResourceObject;

final class SchemaDemo extends ResourceObject
{
    /** @var array{x: int, y: int} */
    public $body;

    #[JsonSchema(schema: 'point.json', params: 'point-params.json')]
    public function onGet(int $x = 3, int $y = 4): static
    {
        $this->body = ['x' => $x, 'y' => $y];

        return $this;
    }
}
