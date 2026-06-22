# @pinge/react-native-steps

React Native library to track steps with available hardware sensors

## Installation

```sh
npm install @pinge/react-native-steps
```

## Usage

```ts
import { start } from '@pinge/react-native-steps';

// ...
start({
  onStep: (event) => {
    console.log(`Total steps: ${event.steps}`);
    console.log(`Sensor type: ${event.sensor}`);
    console.log(`Session started: ${event.startDate}`);
    console.log(`Distance: ${event.distance}`);
    // iOS only
    console.log(`Floors ascended: ${event.floorsAscended}`);
    console.log(`Floors descended: ${event.floorsDescended}`);
  },
  // Android only
  notification: {
    title: 'Sidecar',
    text: "I've counted {{steps}} steps 🚶",
    channel: 'Step Counter',
  },
});
```

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT

---

Made with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
