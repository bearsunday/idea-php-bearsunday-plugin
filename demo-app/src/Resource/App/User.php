<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Resource\App;

use BEAR\Resource\ResourceObject;

final class User extends ResourceObject
{
    public function onGet(int $id = 1): static
    {
        $this->body = [
            'id' => $id,
            'name' => 'BEAR',
        ];

        return $this;
    }
}
