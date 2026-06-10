# MyVendor.MyProject

## Installation

    composer install

## Usage

### Invoke Request

    composer page get /

### Available Commands

    composer serve             // start builtin server
    composer test              // run unit test
    composer tests             // test and quality checks
    composer coverage          // test coverage
    composer cs-fix            // fix the coding standard
    composer doc               // generate API document
    composer run-script --list // list all commands
    
## Links

 * BEAR.Sunday http://bearsunday.github.io/


### Input DTO / MediaQuery Demo

Scalar-first resource for plugin refactoring:

    composer app -- get 'app://self/point?x=6&y=8'

DTO-first resource showing Ray.MediaQuery receives an Input DTO and flattens it to `:x` and `:y` SQL parameters:

    composer app -- get 'app://self/point-dto?x=5&y=12'

Demo files:

* `src/Resource/App/Point.php` - refactoring target (`int $x, int $y`)
* `src/Resource/App/PointDto.php` - DTO + MediaQuery target shape
* `src/Input/PointInput.php` - `#[Input]` DTO
* `src/Query/PointQueryInterface.php` - `#[DbQuery]` interface
* `var/sql/point_distance.sql` - SQL using `:x` and `:y`
