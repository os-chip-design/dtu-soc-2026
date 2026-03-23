#include <firmware_apis.h>
void main()
{
    User_enableIF();
    USER_writeWord(0xf0f0f0f0,0x30000000);
    int read_word;
    read_word = USER_readWord(0x30000000);
    if (read_word == 0xf0f0f0f0)
        return 1;

}