<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Resource\App;

use BEAR\Resource\Annotation\Embed;
use BEAR\Resource\Annotation\Link;
use BEAR\Resource\ResourceObject;

final class Dashboard extends ResourceObject
{
    #[Embed(src: 'app://self/user{?id}', rel: 'user')]
    #[Link(rel: 'profile', href: 'app://self/profile{?id}')]
    public function onGet(int $id = 1): static
    {
        $this->body = [
            'id' => $id,
            'title' => 'Dashboard',
        ];

        return $this;
    }
}
