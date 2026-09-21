<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Resource\App;

use BEAR\Resource\Annotation\JsonSchema;
use BEAR\Resource\ResourceObject;

/**
 * A method that assigns $this->body in more than one branch. The generated schema of such a
 * method is an "anyOf" of the branches rather than one object, which is the shape issue #54 was
 * about: read for its top-level "properties" it named no fields at all.
 */
final class UnionDemo extends ResourceObject
{
    #[JsonSchema('union-demo.json')]
    public function onGet(bool $short = false): static
    {
        if ($short) {
            $this->body = ['id' => 1];

            return $this;
        }

        $this->body = [
            'id' => 1,
            'title' => 'Union',
            'tags' => ['demo'],
        ];

        return $this;
    }
}
