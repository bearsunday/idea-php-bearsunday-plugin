<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Resource\App;

use BEAR\Resource\ResourceInterface;
use BEAR\Resource\ResourceObject;

/**
 * Demo target for BEAR.Resource URI completion/goto in uri() calls.
 */
final class UriDemo extends ResourceObject
{
    public function __construct(
        private readonly ResourceInterface $resource,
    ) {
    }

    public function onGet(): static
    {
        $this->body = [
            'absolute' => (string) $this->resource->uri('app://self/user'),
            'relative' => (string) $this->resource->uri('/profile'),
        ];

        return $this;
    }
}
