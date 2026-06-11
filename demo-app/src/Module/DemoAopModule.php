<?php

declare(strict_types=1);

namespace MyVendor\MyProject\Module;

use MyVendor\MyProject\Annotation\DemoLogged;
use MyVendor\MyProject\Interceptor\DemoLogger;
use Ray\Di\AbstractModule;

final class DemoAopModule extends AbstractModule
{
    protected function configure(): void
    {
        $this->bindInterceptor(
            $this->matcher->any(),
            $this->matcher->annotatedWith(DemoLogged::class),
            [DemoLogger::class],
        );
    }
}
